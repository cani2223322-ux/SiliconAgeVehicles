package com.sva.client;

import java.util.List;

import org.lwjgl.opengl.GL11;

import com.sva.ConfigSVA;
import com.sva.block.AirfieldSVA;
import com.sva.block.BlockRunwaySVA;
import com.sva.init.ModContentSVA;
import com.sva.vehicle.AirfieldMath;
import com.sva.vehicle.RunwayMath;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;

/**
 * Stage D1, ★2: the runway's marks and inlaid lights, drawn over the nearest strip of «Взлётно-посадочная полоса» blocks
 * (RunwayMath, looked up round the player every FIND_EVERY ticks - nothing is stored in the blocks, so every strip the
 * player builds gets them; config vehicles.airfield):
 * <ul>
 * <li>painted at both ends: the threshold «зебра» and the designator from the strip's course for that landing direction
 * (AirfieldMath.designator: along X «09» / «27», along Z «18» / «36»), lit by the world's light by day, a glowing layer at
 * night / in rain;</li>
 * <li>on a strip with lit edges (the «с огнями» blocks): centre-line lights every 2 blocks - for the end the player is
 * nearer to (he lands toward the far one), white, then the last 30 % red / white, the last 10 % red; threshold lights green
 * across the near end, red across the far one - flush inlays, full bright with a halo at night / in rain, dim glass by day.</li>
 * </ul>
 * Fixed-function GL in RenderWorldLastEvent with a polygon offset over the blocks' top; a few hundred quads at most.
 */
public final class RunwayLightsSVA {

    /** Ticks between look-ups of the strip; how far from it (blocks) the marks are drawn / the lights' halos. */
    private static final int FIND_EVERY = 40;
    private static final double DRAW_RANGE = 220, HALO_RANGE = 160;
    private static final float LIGHT_HALF = 0.09F, LIFT = 0.006F;

    private RunwayMath.Runway runway;
    private boolean lit;
    private long foundAt = Long.MIN_VALUE;
    private World foundIn;

    @SubscribeEvent
    public void onRenderLast(RenderWorldLastEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        World w = mc.theWorld;
        if (!ConfigSVA.airfield || w == null || mc.renderViewEntity == null || ModContentSVA.runway == null) {
            return;
        }
        EntityLivingBase v = mc.renderViewEntity;
        float pt = e.partialTicks;
        double px = v.lastTickPosX + (v.posX - v.lastTickPosX) * pt, pz = v.lastTickPosZ + (v.posZ - v.lastTickPosZ) * pt;
        long now = w.getTotalWorldTime();
        if (w != foundIn || now - foundAt >= FIND_EVERY || now < foundAt) {
            foundIn = w;
            foundAt = now;
            runway = RunwayMath.find(AirfieldSVA.view(w), px, pz);
            lit = runway != null && litEdges(w, runway);
        }
        RunwayMath.Runway r = runway;
        if (r == null || r.distance(px, pz) > DRAW_RANGE) {
            return;
        }
        boolean on = AirfieldSVA.autoOn(w, pt);
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT
                | GL11.GL_CURRENT_BIT | GL11.GL_POLYGON_BIT);
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(-1F, -4F);
        mc.entityRenderer.enableLightmap(pt);
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        float y = (float) r.y + LIFT;
        for (int dir = -1; dir <= 1; dir += 2) {
            paint(w, r, dir, y, on);
        }
        if (lit) {
            double along = r.alongX ? px : pz;
            int vdir = along < (r.a0 + r.a1) / 2 ? 1 : -1;             // the viewer lands toward the far end
            lights(r, vdir, y + 0.002F, on, px, pz);
        }
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
        mc.entityRenderer.disableLightmap(pt);
        GL11.glPopAttrib();
        GL11.glPopMatrix();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** Any lit-edge block along the strip's two edge rows. */
    private static boolean litEdges(World w, RunwayMath.Runway r) {
        int by = (int) Math.round(r.y) - 1;
        int k0 = (int) Math.floor(r.c - r.width / 2.0 + 0.01), k1 = (int) Math.floor(r.c + r.width / 2.0 - 0.01);
        for (int a = (int) Math.floor(r.a0); a < (int) Math.floor(r.a1); a++) {
            for (int k : new int[] {k0, k1}) {
                int x = r.alongX ? a : k, z = r.alongX ? k : a;
                if (w.getBlock(x, by, z) == ModContentSVA.runway && BlockRunwaySVA.variant(w.getBlockMetadata(x, by, z)) == BlockRunwaySVA.LIT) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The «зебра» and the designator at the end approached in direction dir. */
    private static void paint(World w, RunwayMath.Runway r, int dir, float y, boolean on) {
        double[] t = AirfieldMath.world(r, dir, 0, 2);
        int b = w.getLightBrightnessForSkyBlocks((int) Math.floor(t[0]), (int) Math.round(r.y), (int) Math.floor(t[1]), 0);
        if (on) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            GL11.glColor3f(0.86F, 0.9F, 0.96F);                  // the glowing layer
        } else {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, b & 0xFFFF, b >> 16);
            GL11.glColor3f(0.93F, 0.93F, 0.9F);
        }
        GL11.glBegin(GL11.GL_QUADS);
        double v0 = AirfieldMath.ZEBRA_V0, v1 = v0 + AirfieldMath.ZEBRA_LEN, hw = AirfieldMath.STRIPE_W / 2;
        for (Double u : AirfieldMath.zebra(r.width)) {
            quad(r, dir, u.doubleValue() - hw, v0, u.doubleValue() + hw, v1, y);
        }
        for (double[] c : AirfieldMath.numberCells(AirfieldMath.designator(r, dir), r.width)) {
            quad(r, dir, c[0], c[1], c[2], c[3], y);
        }
        GL11.glEnd();
    }

    /** The centre-line and threshold / end lights for a pilot landing in direction vdir. */
    private static void lights(RunwayMath.Runway r, int vdir, float y, boolean on, double px, double pz) {
        List<Double> cl = AirfieldMath.centreLights(r);
        double len = r.length();
        if (on) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        }
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < cl.size(); i++) {
            double a = cl.get(i).doubleValue();
            double toEnd = vdir > 0 ? r.a1 - a : a - r.a0;
            int idx = vdir > 0 ? i : cl.size() - 1 - i;
            colour(AirfieldMath.centreColour(len, toEnd, idx), on);
            double v = vdir > 0 ? a - r.a0 : r.a1 - a;
            quad(r, vdir, -LIGHT_HALF, v - LIGHT_HALF, LIGHT_HALF, v + LIGHT_HALF, y);
        }
        double half = r.width / 2.0 - 0.3;
        for (int end = 0; end < 2; end++) {
            int d = end == 0 ? vdir : -vdir;                     // the near threshold green, the far end red
            colour(end == 0 ? AirfieldMath.GREEN : AirfieldMath.RED, on);
            for (double u = -half; u <= half + 1e-6; u += 0.6) {
                quad(r, d, u - LIGHT_HALF, 0.12, u + LIGHT_HALF, 0.12 + 2 * LIGHT_HALF, y);
            }
        }
        GL11.glEnd();
        if (!on) {
            return;
        }
        RenderAirfieldSVA.beginGlow();
        for (int i = 0; i < cl.size(); i++) {
            double a = cl.get(i).doubleValue();
            double[] p = AirfieldMath.world(r, 1, 0, a - r.a0);
            double dx = p[0] - px, dz = p[1] - pz, dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > HALO_RANGE) {
                continue;
            }
            double toEnd = vdir > 0 ? r.a1 - a : a - r.a0;
            int idx = vdir > 0 ? i : cl.size() - 1 - i;
            float[] c = rgb(AirfieldMath.centreColour(len, toEnd, idx));
            RenderAirfieldSVA.halo((float) p[0], y + 0.05F, (float) p[1], (float) (0.12 + dist * 0.006), c[0], c[1], c[2], 0.55F);
        }
        for (int end = 0; end < 2; end++) {
            int d = end == 0 ? vdir : -vdir;
            float[] c = rgb(end == 0 ? AirfieldMath.GREEN : AirfieldMath.RED);
            for (double u = -half; u <= half + 1e-6; u += 0.6) {
                double[] p = AirfieldMath.world(r, d, u, 0.12 + LIGHT_HALF);
                double dx = p[0] - px, dz = p[1] - pz, dist = Math.sqrt(dx * dx + dz * dz);
                if (dist <= HALO_RANGE) {
                    RenderAirfieldSVA.halo((float) p[0], y + 0.05F, (float) p[1], (float) (0.14 + dist * 0.007), c[0], c[1], c[2], 0.6F);
                }
            }
        }
        RenderAirfieldSVA.endGlow();
    }

    private static float[] rgb(int colour) {
        switch (colour) {
            case AirfieldMath.RED:
                return new float[] {1F, 0.2F, 0.15F};
            case AirfieldMath.GREEN:
                return new float[] {0.25F, 1F, 0.4F};
            default:
                return new float[] {1F, 0.96F, 0.86F};
        }
    }

    private static void colour(int colour, boolean on) {
        float[] c = rgb(colour);
        float k = on ? 1F : 0.42F;
        GL11.glColor3f(c[0] * k + (on ? 0F : 0.12F), c[1] * k + (on ? 0F : 0.12F), c[2] * k + (on ? 0F : 0.12F));
    }

    /** A flat quad over the strip in its local frame (u across, v along from the threshold of dir). */
    private static void quad(RunwayMath.Runway r, int dir, double u0, double v0, double u1, double v1, float y) {
        double[] a = AirfieldMath.world(r, dir, u0, v0), b = AirfieldMath.world(r, dir, u1, v0), c = AirfieldMath.world(r, dir, u1, v1),
                d = AirfieldMath.world(r, dir, u0, v1);
        GL11.glVertex3d(a[0], y, a[1]);
        GL11.glVertex3d(b[0], y, b[1]);
        GL11.glVertex3d(c[0], y, c[1]);
        GL11.glVertex3d(d[0], y, d[1]);
    }
}
