package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sva.block.AirfieldSVA;
import com.sva.block.BlockApproachLightSVA;
import com.sva.block.BlockPapiSVA;
import com.sva.block.TileEntityAirfieldSVA;
import com.sva.vehicle.AirfieldMath;

import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * Stage D1: the airfield lights' renderer (TESR) - plain coloured geometry, fixed-function GL, no textures:
 * <ul>
 * <li>«Огни ПАПИ» (★1): a dark housing on four legs with two lenses toward the approach (the runway's axis, else the
 * placer); each lens white or red by the local viewer's angle over the unit (AirfieldMath.white with the unit's index in
 * its row), full bright; from behind / switched off by redstone - dark glass. A halo round each lens: faint by day,
 * bright at night / in rain (it grows with the distance, so the row reads from far out on the approach).</li>
 * <li>«Огни приближения» (★2): a grey pole; on the top block a bar of five lamps across the runway's axis - steady warm
 * white at night / in rain (or by redstone), and the sequenced flash (AirfieldMath.flash) as a blue-white flare on the
 * middle lamp running toward the threshold.</li>
 * </ul>
 * The look-ups (the runway, the unit's index, the pole's distance, redstone) are cached in the tile
 * (TileEntityAirfieldSVA.refresh), so a frame costs only the drawing.
 */
public class RenderAirfieldSVA extends TileEntitySpecialRenderer {

    private static final float LENS_R = 0.095F, LENS_Z = 0.19F;
    private static final int SEG = 14;

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float pt) {
        if (!(te instanceof TileEntityAirfieldSVA) || te.getWorldObj() == null) {
            return;
        }
        TileEntityAirfieldSVA a = (TileEntityAirfieldSVA) te;
        a.refresh();
        World w = te.getWorldObj();
        boolean on = a.manual == AirfieldMath.AUTO ? AirfieldSVA.autoOn(w, pt) : a.manual == AirfieldMath.ON;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glTranslated(x + 0.5, y, z + 0.5);
        // the camera (world) relative to the block's centre bottom
        double cx = -x - 0.5, cy = -y, cz = -z - 0.5;
        if (te.getBlockType() instanceof BlockPapiSVA) {
            papi(a, on, cx, cy, cz);
        } else if (te.getBlockType() instanceof BlockApproachLightSVA) {
            pole(a, w, on, cx, cy, cz, w.getTotalWorldTime() + pt);
        }
        GL11.glPopAttrib();
        GL11.glPopMatrix();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    // ---------------------------------------------------------------- PAPI

    private void papi(TileEntityAirfieldSVA a, boolean on, double cx, double cy, double cz) {
        int meta = a.getBlockMetadata();
        float[] f = facing(a, BlockPapiSVA.facing(meta));
        int nominal = AirfieldMath.nominal(meta);
        double dist = Math.sqrt(cx * cx + cz * cz);
        double ang = AirfieldMath.angle(cy - AirfieldMath.LENS_Y, dist);
        boolean ahead = cx * f[0] + cz * f[1] > 0.3;
        boolean white = AirfieldMath.white(ang, nominal, a.index);
        float yaw = (float) Math.toDegrees(Math.atan2(-f[1], f[0]));
        GL11.glPushMatrix();
        GL11.glRotatef(yaw, 0F, 1F, 0F);
        // legs, housing, the hood over the lenses (local: +x toward the approach)
        for (int i = 0; i < 4; i++) {
            float lx = i < 2 ? -0.16F : 0.06F, lz = (i & 1) == 0 ? -0.3F : 0.26F;
            box(lx, 0F, lz, lx + 0.04F, 0.22F, lz + 0.04F, 0.32F, 0.33F, 0.34F);
        }
        box(-0.22F, 0.22F, -0.4F, 0.14F, 0.62F, 0.4F, 0.2F, 0.21F, 0.23F);
        box(0.14F, 0.58F, -0.4F, 0.2F, 0.62F, 0.4F, 0.14F, 0.15F, 0.16F);
        // the lenses: full bright, white / red by the viewer's angle (dark from behind or switched off)
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        boolean lit = on || a.manual == AirfieldMath.AUTO;          // PAPI burns by day too (the halo only at night)
        boolean show = lit && ahead;
        if (show) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        }
        float r = show ? 1F : 0.22F, g = show ? (white ? 0.97F : 0.16F) : 0.2F, b = show ? (white ? 0.9F : 0.1F) : 0.2F;
        for (int s = -1; s <= 1; s += 2) {
            disc(0.142F, (float) AirfieldMath.LENS_Y, s * LENS_Z, LENS_R, r, g, b);
        }
        GL11.glPopMatrix();
        // the halos (camera-facing, additive)
        if (show) {
            float alpha = on ? 0.85F : 0.32F;
            float size = (float) ((on ? 0.28 : 0.14) + dist * (on ? 0.02 : 0.011));
            beginGlow();
            for (int s = -1; s <= 1; s += 2) {
                float lx = 0.142F * f[0] - s * LENS_Z * f[1], lz = 0.142F * f[1] + s * LENS_Z * f[0];
                halo(lx, (float) AirfieldMath.LENS_Y, lz, size, r, g, b, alpha);
            }
            endGlow();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
        }
    }

    // ---------------------------------------------------------------- approach pole

    private void pole(TileEntityAirfieldSVA a, World w, boolean on, double cx, double cy, double cz, double time) {
        boolean top = !(w.getBlock(a.xCoord, a.yCoord + 1, a.zCoord) instanceof BlockApproachLightSVA);
        box(-0.05F, 0F, -0.05F, 0.05F, top ? 0.84F : 1F, 0.05F, 0.55F, 0.56F, 0.58F);
        if (!top) {
            return;
        }
        // the bar across the runway's axis (no runway: across from placement - meta bit 0 = along X)
        boolean barX = a.runway != null ? !a.runway.alongX : (a.getBlockMetadata() & 1) != 0;
        float yaw = barX ? 90F : 0F;                          // local z = the bar
        GL11.glPushMatrix();
        GL11.glRotatef(yaw, 0F, 1F, 0F);
        box(-0.05F, 0.84F, -1.0F, 0.05F, 0.9F, 1.0F, 0.5F, 0.51F, 0.53F);
        for (int i = -2; i <= 2; i++) {
            box(-0.08F, 0.9F, i * 0.4F - 0.08F, 0.08F, 0.98F, i * 0.4F + 0.08F, 0.25F, 0.26F, 0.28F);
        }
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        if (on) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        }
        for (int i = -2; i <= 2; i++) {
            float k = on ? 1F : 0.35F;
            box(-0.06F, 0.98F, i * 0.4F - 0.06F, 0.06F, 1.02F, i * 0.4F + 0.06F, k, k * 0.95F, k * 0.8F);
        }
        GL11.glPopMatrix();
        if (on) {
            double dist = Math.sqrt(cx * cx + (cy - 1) * (cy - 1) + cz * cz);
            beginGlow();
            float size = (float) (0.2 + dist * 0.012);
            for (int i = -2; i <= 2; i++) {
                float lx = barX ? i * 0.4F : 0F, lz = barX ? 0F : i * 0.4F;
                halo(lx, 1.0F, lz, size, 1F, 0.92F, 0.75F, 0.5F);
            }
            if (a.dir != 0) {
                float fl = (float) AirfieldMath.flash(time, a.dist);
                if (fl > 0.01F) {
                    halo(0F, 1.05F, 0F, (float) (0.5 + dist * 0.025) * (0.6F + 0.4F * fl), 0.75F, 0.85F, 1F, fl);
                    halo(0F, 1.05F, 0F, (float) (0.18 + dist * 0.006), 1F, 1F, 1F, fl);
                }
            }
            endGlow();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
        }
    }

    // ---------------------------------------------------------------- pieces

    /** The way the lenses look (world x, z unit): toward the approach of the runway end served, else the placement facing. */
    private static float[] facing(TileEntityAirfieldSVA a, int placed) {
        if (a.runway != null && a.dir != 0) {
            return a.runway.alongX ? new float[] {-a.dir, 0F} : new float[] {0F, -a.dir};
        }
        switch (placed & 3) {
            case 0:
                return new float[] {0F, 1F};
            case 1:
                return new float[] {-1F, 0F};
            case 2:
                return new float[] {0F, -1F};
            default:
                return new float[] {1F, 0F};
        }
    }

    /** A shaded box (top 1, sides 0.8 / 0.65, bottom 0.5). */
    static void box(float x0, float y0, float z0, float x1, float y1, float z1, float r, float g, float b) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor3f(r, g, b);
        GL11.glVertex3f(x0, y1, z0);
        GL11.glVertex3f(x0, y1, z1);
        GL11.glVertex3f(x1, y1, z1);
        GL11.glVertex3f(x1, y1, z0);
        GL11.glColor3f(r * 0.5F, g * 0.5F, b * 0.5F);
        GL11.glVertex3f(x0, y0, z0);
        GL11.glVertex3f(x1, y0, z0);
        GL11.glVertex3f(x1, y0, z1);
        GL11.glVertex3f(x0, y0, z1);
        GL11.glColor3f(r * 0.8F, g * 0.8F, b * 0.8F);
        GL11.glVertex3f(x0, y0, z1);
        GL11.glVertex3f(x1, y0, z1);
        GL11.glVertex3f(x1, y1, z1);
        GL11.glVertex3f(x0, y1, z1);
        GL11.glVertex3f(x0, y0, z0);
        GL11.glVertex3f(x0, y1, z0);
        GL11.glVertex3f(x1, y1, z0);
        GL11.glVertex3f(x1, y0, z0);
        GL11.glColor3f(r * 0.65F, g * 0.65F, b * 0.65F);
        GL11.glVertex3f(x1, y0, z0);
        GL11.glVertex3f(x1, y1, z0);
        GL11.glVertex3f(x1, y1, z1);
        GL11.glVertex3f(x1, y0, z1);
        GL11.glVertex3f(x0, y0, z0);
        GL11.glVertex3f(x0, y0, z1);
        GL11.glVertex3f(x0, y1, z1);
        GL11.glVertex3f(x0, y1, z0);
        GL11.glEnd();
    }

    /** A lens: a disc in the y-z plane at x, facing +x (both sides drawn). */
    private static void disc(float x, float y, float z, float rad, float r, float g, float b) {
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor3f(r, g, b);
        GL11.glVertex3f(x, y, z);
        for (int k = 0; k <= SEG; k++) {
            double t = k * Math.PI * 2 / SEG;
            GL11.glVertex3f(x, y + (float) Math.sin(t) * rad, z + (float) Math.cos(t) * rad);
        }
        GL11.glEnd();
    }

    static void beginGlow() {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glShadeModel(GL11.GL_SMOOTH);
    }

    static void endGlow() {
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glShadeModel(GL11.GL_FLAT);
    }

    /** A camera-facing halo (ActiveRenderInfo's billboard axes, as the particles use): centre alpha, edge 0. */
    static void halo(float x, float y, float z, float size, float r, float g, float b, float alpha) {
        float rx = ActiveRenderInfo.rotationX, rz = ActiveRenderInfo.rotationZ;
        float ux = ActiveRenderInfo.rotationYZ, uy = ActiveRenderInfo.rotationXZ, uz = ActiveRenderInfo.rotationXY;
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(r, g, b, Math.min(1F, alpha));
        GL11.glVertex3f(x, y, z);
        GL11.glColor4f(r, g, b, 0F);
        for (int k = 0; k <= SEG; k++) {
            double t = k * Math.PI * 2 / SEG;
            float c = (float) Math.cos(t) * size, s = (float) Math.sin(t) * size;
            GL11.glVertex3f(x + c * rx + s * ux, y + s * uy, z + c * rz + s * uz);
        }
        GL11.glEnd();
    }
}
