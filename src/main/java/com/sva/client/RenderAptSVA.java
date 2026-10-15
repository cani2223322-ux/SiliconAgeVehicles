package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sva.ConfigSVA;
import com.sva.airport.WindField;
import com.sva.block.AirportDataSVA;
import com.sva.block.TileEntityAptDeviceSVA;
import com.sva.block.TileEntityIlsAntennaSVA;
import com.sva.block.TileEntityIlsShelterSVA;
import com.sva.vehicle.LinerIls;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * Airport wave B1 (mock-up veh_airport_impl_2_models): the airport equipment's models (TESR, on the masters only; shaded
 * flat-colour geometry like the PAPI / approach lights of stage D1 - fixed-function GL, no textures, OptiFine-safe):
 * <ul>
 * <li>the localizer array: five elements across the runway's centre line (the master and its standing parts - a broken
 * one is missing), each a galvanised post with a white boom toward the runway carrying the log-periodic dipoles (longest at
 * the back); the status lamp on the middle post: green CAT II / III, amber CAT I, red off, grey unpaired;</li>
 * <li>the glide slope mast: a three-block lattice tower (its sections - a broken one gone), three antenna panels toward
 * the runway, the red / white top with its obstruction light;</li>
 * <li>the shelter: walls, roof, the door to the placer's side, the air conditioner, the lamp over the door (green
 * powered, red not);</li>
 * <li>the marker beacon: a low cabinet with its crossed dipoles; the RVR transmissometer: a post with its projector head
 * and the lens; the weather station: a mast, the anemometer's cups turning with the wind, the vane into it, the white
 * louvred screen; the windsock: a red / white pole and the orange / white sock blowing downwind, lifted by the wind's speed
 * and fluttering in the gusts (airport.WindField from the synced seed - every client sees the same).</li>
 * </ul>
 * airport.models = false: the antennas and devices as plain boxes.
 * <p>
 * Airport wave B2 (mock-up veh_airport_impl_b2_1_tower): the «Диспетчерская вышка» - a concrete foot with its door, the
 * square shaft with the stair windows (lit at night with power), the flared cab floor, the eight-sided cab leaning out
 * (tinted glass on white mullions; warm-lit at night when powered), the roof with its antenna room, two whip antennas and
 * the obstruction light, the aerodrome beacon (green / white, every second at night) and the airport's ICAO code on the
 * shaft.
 */
@SideOnly(Side.CLIENT)
public class RenderAptSVA extends TileEntitySpecialRenderer {

    static final float[] METAL = {0.62F, 0.64F, 0.66F}, WHITE = {0.9F, 0.9F, 0.88F}, DARK = {0.2F, 0.21F, 0.23F}, RED = {0.8F, 0.15F, 0.12F},
            ORANGE = {0.98F, 0.45F, 0.1F}, BEIGE = {0.8F, 0.77F, 0.68F};

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float pt) {
        World w = te.getWorldObj();
        if (w == null) {
            return;
        }
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glTranslated(x + 0.5, y, z + 0.5);
        double time = w.getTotalWorldTime() + pt;
        double dist = Math.sqrt((x + 0.5) * (x + 0.5) + y * y + (z + 0.5) * (z + 0.5));
        boolean full = ConfigSVA.aptModels;
        if (te instanceof TileEntityIlsAntennaSVA) {
            TileEntityIlsAntennaSVA a = (TileEntityIlsAntennaSVA) te;
            if (a.kind() == TileEntityIlsAntennaSVA.LOC) {
                loc(a, full, dist);
            } else {
                gs(a, full, dist);
            }
        } else if (te instanceof TileEntityIlsShelterSVA) {
            shelter((TileEntityIlsShelterSVA) te, dist);
        } else if (te instanceof TileEntityAptDeviceSVA) {
            device((TileEntityAptDeviceSVA) te, w, time, full);
        } else if (te instanceof com.sva.block.TileEntityTowerSVA) {
            tower((com.sva.block.TileEntityTowerSVA) te, w, time, dist, full);
        }
        GL11.glPopAttrib();
        GL11.glPopMatrix();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    static void box(float x0, float y0, float z0, float x1, float y1, float z1, float[] c) {
        RenderAirfieldSVA.box(x0, y0, z0, x1, y1, z1, c[0], c[1], c[2]);
    }

    /** The facing's yaw for glRotatef (local +x = toward the runway / the facing). */
    static float yaw(int facing) {
        // MathHelper facing: 0 south (+z), 1 west (-x), 2 north (-z), 3 east (+x); local +x rotated onto it
        switch (facing & 3) {
            case 0:
                return -90F;
            case 1:
                return 180F;
            case 2:
                return 90F;
            default:
                return 0F;
        }
    }

    /** The status lamp's colour for a station's category / flags (-1 unpaired). */
    static float[] statusColour(int cat, int flags) {
        if (cat < 0) {
            return new float[] {0.45F, 0.47F, 0.5F};
        }
        if (cat == 0 || (flags & (LinerIls.F_OFF | LinerIls.F_NO_POWER)) != 0) {
            return new float[] {1F, 0.15F, 0.1F};
        }
        return cat >= 2 ? new float[] {0.2F, 1F, 0.35F} : new float[] {1F, 0.7F, 0.15F};
    }

    static void lamp(float x, float y, float z, float[] c, double dist) {
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        RenderAirfieldSVA.box(x - 0.05F, y - 0.05F, z - 0.05F, x + 0.05F, y + 0.05F, z + 0.05F, c[0], c[1], c[2]);
        RenderAirfieldSVA.beginGlow();
        RenderAirfieldSVA.halo(x, y, z, (float) (0.12 + dist * 0.004), c[0], c[1], c[2], 0.6F);
        RenderAirfieldSVA.endGlow();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
    }

    // ---------------------------------------------------------------- the localizer array

    private void loc(TileEntityIlsAntennaSVA a, boolean full, double dist) {
        int[][] parts = a.parts();
        boolean[] have = a.present();
        GL11.glPushMatrix();
        for (int i = -1; i < parts.length; i++) {
            if (i >= 0 && !have[i]) {
                continue;
            }
            float ox = i < 0 ? 0 : parts[i][0], oz = i < 0 ? 0 : parts[i][2];
            GL11.glPushMatrix();
            GL11.glTranslatef(ox, 0F, oz);
            GL11.glRotatef(yaw(a.facing), 0F, 1F, 0F);
            element(full);
            GL11.glPopMatrix();
        }
        GL11.glPopMatrix();
        lamp(0F, 1.12F, 0F, statusColour(a.cat, a.flags), dist);
    }

    /** One element (local +x toward the runway): the post, the boom, the dipoles. */
    private static void element(boolean full) {
        box(-0.06F, 0F, -0.06F, 0.06F, 0.82F, 0.06F, METAL);
        box(-0.16F, 0F, -0.16F, 0.16F, 0.06F, 0.16F, DARK);
        if (!full) {
            box(-0.4F, 0.8F, -0.3F, 0.4F, 0.9F, 0.3F, WHITE);
            return;
        }
        box(-0.45F, 0.8F, -0.025F, 0.45F, 0.86F, 0.025F, WHITE);
        for (int k = 0; k < 6; k++) {
            float x = -0.4F + k * 0.16F, half = 0.42F - k * 0.055F;
            box(x - 0.015F, 0.82F, -half, x + 0.015F, 0.84F, half, WHITE);
        }
        box(0.4F, 0.78F, -0.04F, 0.47F, 0.88F, 0.04F, RED);
    }

    // ---------------------------------------------------------------- the glide slope mast

    private void gs(TileEntityIlsAntennaSVA a, boolean full, double dist) {
        boolean[] have = a.present();
        int sections = 1 + (have.length > 0 && have[0] ? 1 : 0) + (have.length > 1 && have[0] && have[1] ? 1 : 0);
        float h = sections;
        float s = 0.22F;
        GL11.glPushMatrix();
        GL11.glRotatef(yaw(a.facing), 0F, 1F, 0F);
        box(-0.35F, 0F, -0.35F, 0.35F, 0.08F, 0.35F, DARK);
        for (int i = 0; i < 4; i++) {
            float cx = (i & 1) == 0 ? -s : s - 0.05F, cz = i < 2 ? -s : s - 0.05F;
            box(cx, 0.08F, cz, cx + 0.05F, h, cz + 0.05F, (int) h % 2 == 0 ? METAL : METAL);
        }
        if (full) {
            for (float y = 0.3F; y < h; y += 0.5F) {
                box(-s, y, -s, s, y + 0.03F, -s + 0.03F, METAL);
                box(-s, y, s - 0.03F, s, y + 0.03F, s, METAL);
                box(-s, y + 0.25F, -s, -s + 0.03F, y + 0.28F, s, METAL);
                box(s - 0.03F, y + 0.25F, -s, s, y + 0.28F, s, METAL);
            }
            for (int k = 0; k < sections; k++) {
                float y = 0.55F + k * 0.85F;
                box(s, y, -0.18F, s + 0.1F, y + 0.4F, 0.18F, WHITE);
                box(s + 0.1F, y + 0.05F, -0.14F, s + 0.12F, y + 0.35F, 0.14F, DARK);
            }
        } else {
            box(-s, 0.5F, -s, s, h, s, METAL);
        }
        if (sections == 3) {
            box(-s, h - 0.25F, -s, s, h, s, RED);
            box(-s, h - 0.5F, -s, s, h - 0.25F, s, WHITE);
        }
        GL11.glPopMatrix();
        if (sections == 3) {
            lamp(0F, h + 0.08F, 0F, new float[] {1F, 0.1F, 0.08F}, dist);
        }
        lamp(0F, 0.25F, 0.3F, statusColour(a.cat, a.flags), dist);
    }

    // ---------------------------------------------------------------- the shelter

    private void shelter(TileEntityIlsShelterSVA t, double dist) {
        int meta = t.getBlockMetadata();
        GL11.glPushMatrix();
        GL11.glRotatef(yaw(meta), 0F, 1F, 0F);
        box(-0.46F, 0F, -0.46F, 0.46F, 0.82F, 0.46F, BEIGE);
        box(-0.5F, 0.82F, -0.5F, 0.5F, 0.9F, 0.5F, DARK);
        box(0.46F, 0.02F, -0.16F, 0.48F, 0.66F, 0.16F, new float[] {0.32F, 0.36F, 0.42F});
        box(-0.2F, 0.3F, 0.46F, 0.12F, 0.6F, 0.58F, WHITE);
        for (int k = 0; k < 4; k++) {
            box(-0.18F, 0.33F + k * 0.065F, 0.58F, 0.1F, 0.35F + k * 0.065F, 0.59F, DARK);
        }
        GL11.glPopMatrix();
        float[] c = t.clientPowered ? new float[] {0.2F, 1F, 0.35F} : new float[] {1F, 0.15F, 0.1F};
        double r = Math.toRadians(-yaw(meta));
        lamp((float) (Math.cos(r) * 0.5), 0.74F, (float) (Math.sin(r) * 0.5), c, dist);
    }

    // ---------------------------------------------------------------- airport wave B2: the control tower

    static final float[] CONCRETE = {0.78F, 0.77F, 0.74F}, SHAFT = {0.86F, 0.86F, 0.84F}, GLASS = {0.13F, 0.22F, 0.28F},
            GLASS_LIT = {0.62F, 0.56F, 0.36F}, SLIT = {0.25F, 0.28F, 0.32F}, SLIT_LIT = {0.95F, 0.85F, 0.55F};

    /** An eight-sided ring of quads from (y0, radius r0) to (y1, r1), each face shaded by its bearing; caps when asked. */
    static void octa(float y0, float r0, float y1, float r1, float[] c, boolean caps) {
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < 8; i++) {
            double a0 = Math.PI / 8 + i * Math.PI / 4, a1 = a0 + Math.PI / 4, am = (a0 + a1) / 2;
            float sh = (float) (0.7 + 0.3 * Math.cos(am - 0.6));
            GL11.glColor3f(c[0] * sh, c[1] * sh, c[2] * sh);
            GL11.glVertex3f((float) Math.cos(a0) * r0, y0, (float) Math.sin(a0) * r0);
            GL11.glVertex3f((float) Math.cos(a1) * r0, y0, (float) Math.sin(a1) * r0);
            GL11.glVertex3f((float) Math.cos(a1) * r1, y1, (float) Math.sin(a1) * r1);
            GL11.glVertex3f((float) Math.cos(a0) * r1, y1, (float) Math.sin(a0) * r1);
        }
        GL11.glEnd();
        if (caps) {
            GL11.glBegin(GL11.GL_POLYGON);
            GL11.glColor3f(c[0], c[1], c[2]);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 8 + i * Math.PI / 4;
                GL11.glVertex3f((float) Math.cos(a) * r1, y1, (float) Math.sin(a) * r1);
            }
            GL11.glEnd();
            GL11.glBegin(GL11.GL_POLYGON);
            GL11.glColor3f(c[0] * 0.5F, c[1] * 0.5F, c[2] * 0.5F);
            for (int i = 7; i >= 0; i--) {
                double a = Math.PI / 8 + i * Math.PI / 4;
                GL11.glVertex3f((float) Math.cos(a) * r0, y0, (float) Math.sin(a) * r0);
            }
            GL11.glEnd();
        }
    }

    private void tower(com.sva.block.TileEntityTowerSVA t, World w, double time, double dist, boolean full) {
        boolean night = com.sva.vehicle.AirfieldMath.night(w.getCelestialAngle(0F));
        boolean lit = night && t.clientPowered;
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        GL11.glPushMatrix();
        GL11.glRotatef(yaw(t.facing), 0F, 1F, 0F);
        // the foot and its door (local +x: the cab's front)
        box(-0.6F, 0F, -0.6F, 0.6F, 0.35F, 0.6F, CONCRETE);
        box(0.6F, 0.02F, -0.14F, 0.62F, 0.3F, 0.14F, DARK);
        // the shaft with its stair windows
        box(-0.4F, 0.35F, -0.4F, 0.4F, 4.05F, 0.4F, SHAFT);
        if (full) {
            if (lit) {
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            }
            for (float y = 0.7F; y < 3.8F; y += 0.55F) {
                box(0.4F, y, -0.05F, 0.41F, y + 0.3F, 0.05F, lit ? SLIT_LIT : SLIT);
                box(-0.05F, y + 0.2F, 0.4F, 0.05F, y + 0.5F, 0.41F, lit ? SLIT_LIT : SLIT);
            }
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
            // the code's plate
            box(0.4F, 3.3F, -0.3F, 0.42F, 3.55F, 0.3F, DARK);
        }
        // the flared floor, the cab (leaning out), its mullions, the roof
        octa(4.05F, 0.45F, 4.3F, 1.15F, CONCRETE, true);
        if (lit) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 200F, 200F);
        }
        octa(4.3F, 1.05F, 5.45F, 1.25F, lit ? GLASS_LIT : GLASS, false);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
        if (full) {
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 8 + i * Math.PI / 4;
                float x0 = (float) Math.cos(a), z0 = (float) Math.sin(a);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glColor3f(0.92F, 0.92F, 0.9F);
                float hw = 0.035F;
                float nx = -z0 * hw, nz = x0 * hw;
                GL11.glVertex3f(x0 * 1.065F - nx, 4.3F, z0 * 1.065F - nz);
                GL11.glVertex3f(x0 * 1.065F + nx, 4.3F, z0 * 1.065F + nz);
                GL11.glVertex3f(x0 * 1.265F + nx, 5.45F, z0 * 1.265F + nz);
                GL11.glVertex3f(x0 * 1.265F - nx, 5.45F, z0 * 1.265F - nz);
                GL11.glEnd();
            }
            octa(4.85F, 1.155F, 4.9F, 1.165F, WHITE, false);
        }
        octa(5.45F, 1.32F, 5.62F, 1.32F, WHITE, true);
        box(-0.35F, 5.62F, -0.35F, 0.35F, 5.95F, 0.35F, BEIGE);
        if (full) {
            box(-0.25F, 5.95F, -0.25F, -0.21F, 6.85F, -0.21F, METAL);
            box(0.2F, 5.95F, 0.18F, 0.23F, 6.5F, 0.21F, METAL);
            box(-0.06F, 5.95F, -0.06F, 0.06F, 6.15F, 0.06F, DARK);
        }
        GL11.glPopMatrix();
        // the ICAO code on the plate (the font in the world, as a sign's)
        if (full && t.clientIcao.length() > 0 && dist < 48) {
            net.minecraft.client.gui.FontRenderer fr = func_147498_b();
            if (fr != null) {
                GL11.glPushMatrix();
                GL11.glRotatef(yaw(t.facing) + 90F, 0F, 1F, 0F);
                GL11.glTranslatef(0F, 3.5F, 0.43F);
                GL11.glScalef(0.016F, -0.016F, 0.016F);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glDepthMask(false);
                fr.drawString(t.clientIcao, -fr.getStringWidth(t.clientIcao) / 2, 0, 0xFFFFFF);
                GL11.glDepthMask(true);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glPopMatrix();
            }
        }
        // the lights: the obstruction light on the whip; the beacon green / white once a second at night
        if (night) {
            lamp(-0.23F, 6.9F, -0.23F, new float[] {1F, 0.12F, 0.08F}, dist);
            if (t.clientPowered) {
                boolean green = ((long) (time / 20)) % 2 == 0;
                lamp(0F, 6.2F, 0F, green ? new float[] {0.2F, 1F, 0.4F} : new float[] {1F, 1F, 0.95F}, dist * 1.6);
            }
        }
    }

    // ---------------------------------------------------------------- the devices

    private void device(TileEntityAptDeviceSVA d, World w, double time, boolean full) {
        int k = d.kind();
        if (k == AirportDataSVA.K_MARKER) {
            box(-0.3F, 0F, -0.3F, 0.3F, 0.25F, 0.3F, BEIGE);
            box(-0.32F, 0.25F, -0.32F, 0.32F, 0.29F, 0.32F, DARK);
            if (full) {
                box(-0.28F, 0.31F, -0.02F, 0.28F, 0.33F, 0.02F, WHITE);
                box(-0.02F, 0.31F, -0.28F, 0.02F, 0.33F, 0.28F, WHITE);
                box(-0.03F, 0.29F, -0.03F, 0.03F, 0.33F, 0.03F, METAL);
            }
            return;
        }
        if (k == AirportDataSVA.K_RVR) {
            box(-0.2F, 0F, -0.15F, 0.2F, 0.3F, 0.15F, BEIGE);
            box(-0.04F, 0.3F, -0.04F, 0.04F, 1.3F, 0.04F, METAL);
            box(-0.12F, 1.3F, -0.3F, 0.12F, 1.52F, 0.3F, WHITE);
            box(-0.09F, 1.33F, 0.3F, 0.09F, 1.49F, 0.33F, DARK);
            box(-0.09F, 1.33F, -0.33F, 0.09F, 1.49F, -0.3F, DARK);
            return;
        }
        AirportClientSVA.State st = AirportClientSVA.state;
        WindField.Wind wind = WindField.wind(st.windSeed, time, w.isRaining(), w.isThundering(), AirportDataSVA.WX_HEIGHT);
        float to = (float) (wind.from + 180);
        if (k == AirportDataSVA.K_WX) {
            box(-0.25F, 0F, -0.25F, 0.25F, 0.08F, 0.25F, DARK);
            box(-0.035F, 0.08F, -0.035F, 0.035F, 2.3F, 0.035F, METAL);
            // the louvred screen on its arm
            box(0.035F, 1.0F, -0.02F, 0.3F, 1.03F, 0.02F, METAL);
            box(0.18F, 0.85F, -0.15F, 0.48F, 1.2F, 0.15F, WHITE);
            for (int i = 0; i < 4; i++) {
                box(0.177F, 0.9F + i * 0.07F, -0.15F, 0.18F, 0.92F + i * 0.07F, 0.15F, METAL);
            }
            // the anemometer: three cups turning with the speed
            GL11.glPushMatrix();
            GL11.glTranslatef(0F, 2.3F, 0F);
            GL11.glRotatef((float) (time * wind.gust * 6 % 360), 0F, 1F, 0F);
            for (int i = 0; i < 3; i++) {
                GL11.glPushMatrix();
                GL11.glRotatef(i * 120F, 0F, 1F, 0F);
                box(0F, 0.02F, -0.012F, 0.22F, 0.04F, 0.012F, METAL);
                box(0.2F, -0.02F, -0.05F, 0.27F, 0.08F, 0.05F, DARK);
                GL11.glPopMatrix();
            }
            GL11.glPopMatrix();
            // the vane: its tail downwind, the arrow into the wind
            GL11.glPushMatrix();
            GL11.glTranslatef(0F, 2.05F, 0F);
            GL11.glRotatef((float) (90 - wind.from), 0F, 1F, 0F);
            box(-0.25F, -0.01F, -0.01F, 0.25F, 0.01F, 0.01F, METAL);
            box(-0.3F, -0.06F, -0.005F, -0.18F, 0.06F, 0.005F, WHITE);
            box(0.22F, -0.03F, -0.03F, 0.28F, 0.03F, 0.03F, RED);
            GL11.glPopMatrix();
            return;
        }
        // the windsock: the pole, the sock blowing toward `to`, lifted by the speed (full at 28 km/h), fluttering
        for (int i = 0; i < 4; i++) {
            box(-0.03F, i * 0.55F, -0.03F, 0.03F, (i + 1) * 0.55F, 0.03F, i % 2 == 0 ? RED : WHITE);
        }
        double lift = Math.min(1, wind.gust / 7.7);
        float droop = (float) (80 - 80 * lift + 4 * Math.sin(time * 0.9 + wind.gust));
        GL11.glPushMatrix();
        GL11.glTranslatef(0F, 2.15F, 0F);
        GL11.glRotatef(-to + 90F, 0F, 1F, 0F);
        GL11.glRotatef(-droop, 0F, 0F, 1F);
        box(-0.01F, -0.2F, -0.2F, 0.02F, 0.2F, 0.2F, METAL);
        float x = 0.02F;
        for (int i = 0; i < 5; i++) {
            float r0 = 0.18F - i * 0.022F, r1 = r0 - 0.022F;
            float len = 0.2F;
            float wob = (float) (0.015 * Math.sin(time * 1.7 + i * 1.3) * (1 - lift * 0.5));
            box(x, -r0 + wob, -r0, x + len, r0 + wob, r0, i % 2 == 0 ? ORANGE : WHITE);
            x += len;
            r0 = r1;
        }
        GL11.glPopMatrix();
    }
}
