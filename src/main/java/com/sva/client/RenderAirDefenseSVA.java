package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sva.ConfigSVA;
import com.sva.airdefense.AdShapeMath;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.TileEntityAirDefenseSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;

/**
 * Multiblock wave: the air-defence structures' renderer (one TESR on the master, the parts are invisible) - the model of
 * its kind (AdModelSVA) at the footprint's centre, turned to the structure's facing, the moving parts at their angles
 * between the last two ticks (partialTicks):
 * - survey radar: the reflector turns once per sweep (6 s) while powered;
 * - tracking radar: the yoke turns (azimuth) and the dish tilts (elevation) to its target, else keeps searching round;
 * - height finder: the antenna nods +-20 deg once per 3 s;
 * - AA gun: the turret turns, the barrels lift to the target; on a burst they recoil and the muzzles flash;
 * - missile system: the turntable and the tube box follow the target; a missile per loaded tube (the rails empty as it
 *   launches, filled again after the reload while the stock lasts);
 * - command post: the small antenna on the link tower turns, the screens glow, the beacon flashes amber in an alarm.
 * Status lamps on every cabinet: power (green), lock (amber), empty (red), alarm (red, blinking).
 * A compact (1x1, an older world's block with no room) structure is the same model scaled into its block. Beyond
 * airdefense.modelDetailDistance the small details (bolts, rungs, louvres) are left out. Config airdefense.models off:
 * the footprint as a plain textured box.
 */
@SideOnly(Side.CLIENT)
public class RenderAirDefenseSVA extends TileEntitySpecialRenderer {

    public static final int LAMP_POWER = 0x3CFF6E, LAMP_LOCK = 0xFFAA28, LAMP_RED = 0xFF3228, BEACON = 0xFFAA28;
    /** Tier stripes (vehicles' TIER colours): HV, EV; MV draws the black-yellow band. */
    public static final int ACCENT_HV = 0xFF8C32, ACCENT_EV = 0xE14646, ACCENT_MV = 0xFFC446;

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float pt) {
        if (!(te instanceof TileEntityAirDefenseSVA)) {
            return;
        }
        TileEntityAirDefenseSVA t = (TileEntityAirDefenseSVA) te;
        int shape = t.shape();
        AdModelSVA m = ConfigSVA.adModels ? AdModelSVA.get(AdShapeMath.MODEL[shape]) : null;
        GL11.glPushMatrix();
        GL11.glTranslated(x + 0.5, y, z + 0.5);
        boolean multi = t.structState() == TileEntityAirDefenseSVA.MULTI;
        if (multi) {
            double[] c = AdShapeMath.centre(shape, t.facing());
            GL11.glTranslated(c[0], 0, c[1]);
        }
        GL11.glRotatef(AdShapeMath.modelYaw(t.facing()), 0F, 1F, 0F);
        if (m == null) {
            box(t, shape, multi);
        } else {
            if (!multi) {
                float s = AdShapeMath.compactScale(shape);
                GL11.glScalef(s, s, s);
            }
            double d2 = (x + 0.5) * (x + 0.5) + (y + 0.5) * (y + 0.5) + (z + 0.5) * (z + 0.5);
            double lod = ConfigSVA.adModelDetailDistance;
            long now = te.getWorldObj() == null ? 0 : te.getWorldObj().getTotalWorldTime();
            draw(m, shape, t, pt, now, d2 <= lod * lod);
        }
        GL11.glPopMatrix();
    }

    public static int accent(int shape) {
        return shape == AdShapeMath.MISSILE ? ACCENT_EV : shape == AdShapeMath.HEIGHT || shape == AdShapeMath.GUN ? ACCENT_MV : ACCENT_HV;
    }

    /** MV (the gun, the height finder): livery I «Рабочая» - the hazard band. */
    public static boolean hazard(int shape) {
        return shape == AdShapeMath.HEIGHT || shape == AdShapeMath.GUN;
    }

    /**
     * The model in model space (x forward, y up, the footprint's centre at the origin). `t` null: the item - at rest, the
     * power lamp on, all four missiles on the rails.
     */
    public static void draw(AdModelSVA m, int shape, TileEntityAirDefenseSVA t, float pt, long now, boolean detail) {
        m.begin(accent(shape), hazard(shape), detail);
        m.part("base");
        int st = t == null ? TileEntityAirDefenseSVA.ST_POWER : t.clientState();
        float yaw = t == null ? 0F : AdShapeMath.lerpAngle(t.yawPrev, t.yaw, pt);
        float pitch = t == null ? defaultPitch(shape) : t.pitchPrev + (t.pitch - t.pitchPrev) * pt;
        float spin = t == null ? 30F : t.spinPrev + (t.spin - t.spinPrev) * pt;
        switch (shape) {
            case AdShapeMath.SURVEY:
                GL11.glPushMatrix();
                m.turn("rot", spin, 0F);
                m.part("rot");
                GL11.glPopMatrix();
                break;
            case AdShapeMath.TRACK:
                GL11.glPushMatrix();
                m.turn("az", yaw, 0F);
                m.part("az");
                m.turn("el", 0F, pitch);
                m.part("el");
                GL11.glPopMatrix();
                break;
            case AdShapeMath.HEIGHT:
                GL11.glPushMatrix();
                m.turn("nod", 0F, pitch);
                m.part("nod");
                GL11.glPopMatrix();
                break;
            case AdShapeMath.GUN: {
                GL11.glPushMatrix();
                m.turn("az", yaw, 0F);
                m.part("az");
                m.turn("el", 0F, pitch);
                m.part("el");
                float since = t == null ? 99F : now - t.lastShot + pt;
                GL11.glTranslatef(-AdShapeMath.recoil(since), 0F, 0F);
                m.part("rec");
                if (since < AdShapeMath.FLASH_TICKS) {
                    flash(m.point("muzzle0"), since);
                    flash(m.point("muzzle1"), since);
                }
                GL11.glPopMatrix();
                break;
            }
            case AdShapeMath.MISSILE: {
                GL11.glPushMatrix();
                m.turn("az", yaw, 0F);
                m.part("az");
                m.turn("el", 0F, pitch);
                m.part("el");
                int n = t == null ? AdShapeMath.TUBES : AdShapeMath.visibleMissiles(t.clientAmmo(), t.reloadingTubes());
                for (int i = 0; i < n; i++) {
                    m.part(MISSILES[i]);
                }
                GL11.glPopMatrix();
                break;
            }
            case AdShapeMath.KP: {
                GL11.glPushMatrix();
                m.turn("rot", spin, 0F);
                m.part("rot");
                GL11.glPopMatrix();
                boolean alarm = (st & TileEntityAirDefenseSVA.ST_ALARM) != 0;
                m.part("beacon", BEACON, alarm && AdShapeMath.blink(now));
                m.part("btn");
                break;
            }
            default:
                break;
        }
        boolean on = (st & TileEntityAirDefenseSVA.ST_POWER) != 0;
        m.part("lamp_pow", LAMP_POWER, on);
        m.part("lamp_lock", LAMP_LOCK, on && (st & TileEntityAirDefenseSVA.ST_LOCK) != 0);
        m.part("lamp_empty", LAMP_RED, (st & TileEntityAirDefenseSVA.ST_EMPTY) != 0);
        m.part("lamp_alarm", LAMP_RED, (st & TileEntityAirDefenseSVA.ST_ALARM) != 0 && AdShapeMath.blink(now));
        m.end();
    }

    private static final String[] MISSILES = {"m0", "m1", "m2", "m3"};

    private static float defaultPitch(int shape) {
        return shape == AdShapeMath.HEIGHT ? 8F : shape == AdShapeMath.KP || shape == AdShapeMath.SURVEY ? 0F : AdShapeMath.drive(shape)[2] + 10F;
    }

    /** A muzzle flash: three crossed additive quads round the muzzle, shrinking over FLASH_TICKS. */
    private static void flash(float[] p, float since) {
        if (p == null) {
            return;
        }
        float s = 0.32F * (1F - since / AdShapeMath.FLASH_TICKS) + 0.08F;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        Tessellator tz = Tessellator.instance;
        tz.startDrawingQuads();
        tz.setColorRGBA_F(1F, 0.78F, 0.3F, 0.85F);
        float x = p[0] + s * 0.9F, y = p[1], z = p[2];
        tz.addVertex(x - s, y - s * 0.6F, z);
        tz.addVertex(x + s * 1.6F, y, z);
        tz.addVertex(x - s, y + s * 0.6F, z);
        tz.addVertex(x - s * 1.2F, y, z);
        tz.addVertex(x - s, y, z - s * 0.6F);
        tz.addVertex(x + s * 1.6F, y, z);
        tz.addVertex(x - s, y, z + s * 0.6F);
        tz.addVertex(x - s * 1.2F, y, z);
        tz.addVertex(x, y - s * 0.7F, z - s * 0.7F);
        tz.addVertex(x, y + s * 0.7F, z - s * 0.7F);
        tz.addVertex(x, y + s * 0.7F, z + s * 0.7F);
        tz.addVertex(x, y - s * 0.7F, z + s * 0.7F);
        tz.draw();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
        GL11.glDepthMask(true);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_LIGHTING);
    }

    /** Models off: the footprint (W x D, one block high) as a box with the block's old faces. */
    private void box(TileEntityAirDefenseSVA t, int shape, boolean multi) {
        Block b = t.getBlockType();
        if (!(b instanceof BlockAirDefenseSVA)) {
            return;
        }
        int meta = t.getBlockMetadata();
        float hx = multi ? AdShapeMath.D[shape] / 2F : 0.5F, hz = multi ? AdShapeMath.W[shape] / 2F : 0.5F;
        bindTexture(TextureMap.locationBlocksTexture);
        Tessellator tz = Tessellator.instance;
        GL11.glDisable(GL11.GL_CULL_FACE);
        IIcon top = b.getIcon(1, meta), side = b.getIcon(2, meta), bottom = b.getIcon(0, meta);
        tz.startDrawingQuads();
        tz.setColorOpaque_F(1F, 1F, 1F);
        tz.setNormal(0F, 1F, 0F);
        quad(tz, top, -hx, 1, -hz, hx, 1, -hz, hx, 1, hz, -hx, 1, hz);
        tz.setNormal(0F, -1F, 0F);
        quad(tz, bottom, -hx, 0, hz, hx, 0, hz, hx, 0, -hz, -hx, 0, -hz);
        tz.setNormal(1F, 0F, 0F);
        quad(tz, side, hx, 0, -hz, hx, 0, hz, hx, 1, hz, hx, 1, -hz);
        tz.setNormal(-1F, 0F, 0F);
        quad(tz, side, -hx, 0, hz, -hx, 0, -hz, -hx, 1, -hz, -hx, 1, hz);
        tz.setNormal(0F, 0F, 1F);
        quad(tz, side, hx, 0, hz, -hx, 0, hz, -hx, 1, hz, hx, 1, hz);
        tz.setNormal(0F, 0F, -1F);
        quad(tz, side, -hx, 0, -hz, hx, 0, -hz, hx, 1, -hz, -hx, 1, -hz);
        tz.draw();
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    private static void quad(Tessellator tz, IIcon i, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3) {
        tz.addVertexWithUV(x0, y0, z0, i.getMinU(), i.getMaxV());
        tz.addVertexWithUV(x1, y1, z1, i.getMaxU(), i.getMaxV());
        tz.addVertexWithUV(x2, y2, z2, i.getMaxU(), i.getMinV());
        tz.addVertexWithUV(x3, y3, z3, i.getMinU(), i.getMinV());
    }
}
