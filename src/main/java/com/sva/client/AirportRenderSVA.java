package com.sva.client;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.sva.ConfigSVA;
import com.sva.airport.IlsPairing;
import com.sva.airport.RunwayLayout;
import com.sva.init.ModContentSVA;
import com.sva.vehicle.AirfieldMath;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;

/**
 * Airport wave B1 (plan-next §9, items 24-26; mock-ups veh_airport_impl_3_markings, _4_lights): the runways defined by
 * their «Торец полосы» markers (AirportClientSVA's copy) - their paint and their lights, drawn in RenderWorldLastEvent with
 * fixed-function GL (OptiFine-safe), batched PER RUNWAY, never a TESR per block:
 * <ul>
 * <li><b>Paint</b> (RunwayLayout.paint: the piano keys, the designators, the centre line, the touchdown zone, the aiming
 * point, the side stripes): ONE display list per runway, built once (and again when the runway changes, or every REBUILD
 * ticks while part of it lay in unloaded chunks); each piece only over a runway block, with that block's light (the
 * lightmap does day and night by itself), a polygon offset over the surface.</li>
 * <li><b>Lights</b> (RunwayLayout.lights for the viewer's landing direction: edge white / yellow, centre line white / red,
 * the touchdown zone, the threshold's green and the far end's red, the ALS with its sequenced flasher): camera-facing
 * sprites of a soft glow texture, additive, one batch a frame; their size grows with the distance (RunwayLayout.spriteSize)
 * so they keep their size on the screen far out; seen through the fog with airport.lightsFar. The brightness step is the
 * end's mode (AUTO: night / rain / the RVR - RunwayLayout.step).</li>
 * </ul>
 * Config: airport.paint, airport.lights, airport.lightsFar, airport.drawDistance.
 * <p>
 * Airport wave B2: the approach lights burn at their own step (the tower's approach step; AirportClientSVA.alsStep), and
 * the taxi lights («Огонь рулёжки» - AirportClientSVA.taxi) in the same kind of batch: the blue edges and the yellow stands
 * at the taxiways' step (the nearest tower's), the green centre line at that step - or, while a tower leads anybody along a
 * route («следуй за мной»), only the lights lit ahead of the cleared aircraft, brighter; the red stop bars (five lights
 * across the taxiway) while they burn (D_TAXI).
 */
@SideOnly(Side.CLIENT)
public final class AirportRenderSVA {

    /** Ticks between rebuilds of a list that had pieces over unloaded chunks. */
    private static final int REBUILD = 100;
    private static final float LIFT = 0.007F;

    /** Per runway key: {display list, built for state version, built at tick, partial (1)}. */
    private final Map<String, long[]> lists = new HashMap<String, long[]>();
    private static int glowTex = -1;

    @SubscribeEvent
    public void onRenderLast(RenderWorldLastEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        World w = mc.theWorld;
        if (w == null || mc.renderViewEntity == null || !ConfigSVA.aptPaint && !ConfigSVA.aptLights) {
            return;
        }
        AirportClientSVA.State st = AirportClientSVA.state;
        if (st.runways.isEmpty() && AirportClientSVA.taxi.length == 0) {
            return;
        }
        EntityLivingBase v = mc.renderViewEntity;
        float pt = e.partialTicks;
        double px = v.lastTickPosX + (v.posX - v.lastTickPosX) * pt, py = v.lastTickPosY + (v.posY - v.lastTickPosY) * pt + v.getEyeHeight(),
                pz = v.lastTickPosZ + (v.posZ - v.lastTickPosZ) * pt;
        long now = w.getTotalWorldTime();
        double time = now + pt;
        boolean night = AirfieldMath.night(w.getCelestialAngle(pt)), rain = w.isRaining();
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_CURRENT_BIT
                | GL11.GL_POLYGON_BIT | GL11.GL_FOG_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        for (AirportClientSVA.Rwy rw : st.runways) {
            IlsPairing.Rwy r = rw.r;
            double[] l = r.local(1, px, pz);
            double da = l[0] < 0 ? -l[0] : l[0] > r.len ? l[0] - r.len : 0, dc = Math.max(0, Math.abs(l[1]) - r.width / 2.0);
            double dist = Math.sqrt(da * da + dc * dc);
            if (dist > ConfigSVA.aptDrawDistance) {
                continue;
            }
            if (ConfigSVA.aptPaint && dist < Math.max(200, mc.gameSettings.renderDistanceChunks * 16 + 32)) {
                paint(w, rw, st.version, now, pt);
            }
            if (ConfigSVA.aptLights) {
                int dir = l[0] < r.len / 2 ? 1 : -1;
                int step = AirportClientSVA.step(rw, dir, night, rain);
                int alsStep = AirportClientSVA.alsStep(rw, dir, night, rain);
                if (step > 0 || alsStep > 0) {
                    lights(rw, dir, step, alsStep, px, py, pz, time);
                }
            }
        }
        if (ConfigSVA.aptLights && AirportClientSVA.taxi.length > 0) {
            taxi(px, py, pz, time, night, rain);                    // airport wave B2: the taxi lights
        }
        GL11.glPopAttrib();
        GL11.glPopMatrix();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    // ---------------------------------------------------------------- paint

    private void paint(World w, AirportClientSVA.Rwy rw, int version, long now, float pt) {
        String k = rw.r.key();
        long[] l = lists.get(k);
        if (l == null || l[1] != version || l[3] != 0 && now - l[2] > REBUILD) {
            if (l != null) {
                GLAllocation.deleteDisplayLists((int) l[0]);
            }
            int id = GLAllocation.generateDisplayLists(1);
            boolean partial = build(w, rw.r, id);
            l = new long[] {id, version, now, partial ? 1 : 0};
            lists.put(k, l);
        }
        Minecraft.getMinecraft().entityRenderer.enableLightmap(pt);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(-1F, -4F);
        GL11.glCallList((int) l[0]);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        Minecraft.getMinecraft().entityRenderer.disableLightmap(pt);
    }

    /** The runway's paint into display list `id`. @return some piece lay over an unloaded chunk */
    private static boolean build(World w, IlsPairing.Rwy r, int id) {
        List<double[]> quads = RunwayLayout.paint(r.len, r.width, r.nameA, r.nameB);
        boolean partial = false;
        int by = (int) Math.floor(r.elev) - 1;
        float y = (float) r.elev + LIFT;
        GL11.glNewList(id, GL11.GL_COMPILE);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        for (double[] q : quads) {
            double[] c = r.world(1, (q[1] + q[3]) / 2, (q[0] + q[2]) / 2);
            int cx = (int) Math.floor(c[0]), cz = (int) Math.floor(c[1]);
            if (!w.blockExists(cx, by, cz)) {
                partial = true;
            } else if (w.getBlock(cx, by, cz) != ModContentSVA.runway && w.getBlock(cx, by, cz) != ModContentSVA.runwayEnd) {
                continue;                                           // only over the runway's own blocks
            }
            t.setBrightness(w.blockExists(cx, by + 1, cz) ? w.getLightBrightnessForSkyBlocks(cx, by + 1, cz, 0) : 0xF000F0);
            t.setColorOpaque_F(0.93F, 0.93F, 0.9F);
            double[] a = r.world(1, q[1], q[0]), b = r.world(1, q[1], q[2]), cc = r.world(1, q[3], q[2]), d = r.world(1, q[3], q[0]);
            t.addVertex(a[0], y, a[1]);
            t.addVertex(b[0], y, b[1]);
            t.addVertex(cc[0], y, cc[1]);
            t.addVertex(d[0], y, d[1]);
        }
        t.draw();
        GL11.glEndList();
        return partial;
    }

    // ---------------------------------------------------------------- lights

    /** Per runway key + dir + version: the lights' world places {x, y, z} and their colour / run. */
    private final Map<String, Object[]> lightCache = new HashMap<String, Object[]>();

    private void lights(AirportClientSVA.Rwy rw, int dir, int step, int alsStep, double px, double py, double pz, double time) {
        IlsPairing.Rwy r = rw.r;
        String ck = r.key() + (dir > 0 ? "+" : "-") + AirportClientSVA.state.version;
        Object[] c = lightCache.get(ck);
        if (c == null) {
            if (lightCache.size() > 64) {
                lightCache.clear();
            }
            List<RunwayLayout.Light> l = RunwayLayout.lights(r.len, r.width, dir, r.a.als != 0, r.b.als != 0);
            float[] pos = new float[l.size() * 3];
            int[] col = new int[l.size()];
            float[] run = new float[l.size()];
            boolean[] alsL = new boolean[l.size()];
            for (int i = 0; i < l.size(); i++) {
                RunwayLayout.Light li = l.get(i);
                alsL[i] = dir > 0 ? li.v < -0.5 : li.v > r.len + 0.5;   // wave B2: before the landing threshold - the ALS
                double[] wp = r.world(1, li.v, li.u);
                pos[i * 3] = (float) wp[0];
                pos[i * 3 + 1] = (float) (r.elev + li.h);
                pos[i * 3 + 2] = (float) wp[1];
                col[i] = li.colour;
                run[i] = (float) li.run;
            }
            c = new Object[] {pos, col, run, alsL};
            lightCache.put(ck, c);
        }
        float[] pos = (float[]) c[0];
        int[] col = (int[]) c[1];
        float[] run = (float[]) c[2];
        boolean[] alsL = (boolean[]) c[3];
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glow());
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        if (ConfigSVA.aptLightsFar) {
            GL11.glDisable(GL11.GL_FOG);
        }
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        float rx = ActiveRenderInfo.rotationX, rz = ActiveRenderInfo.rotationZ;
        float ux = ActiveRenderInfo.rotationYZ, uy = ActiveRenderInfo.rotationXZ, uz = ActiveRenderInfo.rotationXY;
        double lvlR = RunwayLayout.LEVEL[Math.max(0, Math.min(RunwayLayout.STEPS, step))];
        double lvlA = RunwayLayout.LEVEL[Math.max(0, Math.min(RunwayLayout.STEPS, alsStep))];
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < col.length; i++) {
            int stp = alsL[i] ? alsStep : step;
            if (stp <= 0) {
                continue;
            }
            float x = pos[i * 3], y = pos[i * 3 + 1], z = pos[i * 3 + 2];
            double dx = x - px, dy = y - py, dz = z - pz, dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double k = alsL[i] ? lvlA : lvlR;
            if (col[i] == RunwayLayout.FLASH) {
                k = RunwayLayout.alsFlash(time, run[i]);
                if (k < 0.02) {
                    continue;
                }
            }
            float s = (float) (RunwayLayout.spriteSize(dist, stp) * (col[i] == RunwayLayout.FLASH ? 1.8 : 1.0));
            float[] rgb = rgb(col[i]);
            GL11.glColor4f(rgb[0], rgb[1], rgb[2], (float) Math.min(1, 0.35 + 0.65 * k));
            GL11.glTexCoord2f(0F, 0F);
            GL11.glVertex3f(x - rx * s - ux * s, y - uy * s, z - rz * s - uz * s);
            GL11.glTexCoord2f(0F, 1F);
            GL11.glVertex3f(x - rx * s + ux * s, y + uy * s, z - rz * s + uz * s);
            GL11.glTexCoord2f(1F, 1F);
            GL11.glVertex3f(x + rx * s + ux * s, y + uy * s, z + rz * s + uz * s);
            GL11.glTexCoord2f(1F, 0F);
            GL11.glVertex3f(x + rx * s - ux * s, y - uy * s, z + rz * s - uz * s);
        }
        GL11.glEnd();
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
    }

    static float[] rgb(int colour) {
        switch (colour) {
            case RunwayLayout.YELLOW:
                return new float[] {1F, 0.78F, 0.22F};
            case RunwayLayout.RED:
                return new float[] {1F, 0.16F, 0.1F};
            case RunwayLayout.GREEN:
                return new float[] {0.22F, 1F, 0.38F};
            case RunwayLayout.FLASH:
                return new float[] {0.85F, 0.92F, 1F};
            case RunwayLayout.BLUE:
                return new float[] {0.25F, 0.45F, 1F};
            default:
                return new float[] {1F, 0.94F, 0.82F};
        }
    }

    /** The soft round glow (32 x 32, a bright core and a gaussian rim), made once. */
    static int glow() {
        if (glowTex >= 0) {
            return glowTex;
        }
        int n = 32;
        ByteBuffer b = BufferUtils.createByteBuffer(n * n * 4);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1, r = Math.sqrt(dx * dx + dy * dy);
                double a = Math.max(0, Math.exp(-r * r * 5) - 0.007) + (r < 0.18 ? 0.6 * (1 - r / 0.18) : 0);
                int ai = (int) Math.max(0, Math.min(255, Math.round(a * 255)));
                b.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) ai);
            }
        }
        b.flip();
        glowTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glowTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, n, n, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, b);
        return glowTex;
    }

    // ---------------------------------------------------------------- airport wave B2: the taxi lights

    /** Per taxi version: each stop bar's across direction {ux, uz} (from its nearest neighbour light), by index. */
    private int stopsFor = -2;
    private float[] stopAcross = new float[0];

    private void stopDirs(int[] tx) {
        int n = tx.length / 4;
        stopAcross = new float[n * 2];
        for (int i = 0; i < n; i++) {
            if (tx[i * 4 + 3] != com.sva.block.BlockTaxiLightSVA.STOP) {
                continue;
            }
            int best = -1;
            double bd = 100;
            for (int j = 0; j < n; j++) {
                int k = tx[j * 4 + 3];
                if (j == i || k == com.sva.block.BlockTaxiLightSVA.EDGE) {
                    continue;
                }
                double d = (tx[j * 4] - tx[i * 4]) * (tx[j * 4] - tx[i * 4]) + (tx[j * 4 + 2] - tx[i * 4 + 2]) * (tx[j * 4 + 2] - tx[i * 4 + 2]);
                if (d < bd) {
                    bd = d;
                    best = j;
                }
            }
            double ax = 1, az = 0;
            if (best >= 0) {
                double ex = tx[best * 4] - tx[i * 4], ez = tx[best * 4 + 2] - tx[i * 4 + 2], len = Math.sqrt(ex * ex + ez * ez);
                ax = -ez / len;
                az = ex / len;
            }
            stopAcross[i * 2] = (float) ax;
            stopAcross[i * 2 + 1] = (float) az;
        }
        stopsFor = AirportClientSVA.taxiVersion;
    }

    private void taxi(double px, double py, double pz, double time, boolean night, boolean rain) {
        int[] tx = AirportClientSVA.taxi;
        AirportClientSVA.Lit lit = AirportClientSVA.lit;
        boolean same = lit.version == AirportClientSVA.taxiVersion;
        if (stopsFor != AirportClientSVA.taxiVersion || stopAcross.length != tx.length / 2) {
            stopDirs(tx);
        }
        double max = Math.min(ConfigSVA.aptDrawDistance, 800);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glow());
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        if (ConfigSVA.aptLightsFar) {
            GL11.glDisable(GL11.GL_FOG);
        }
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        float rx = ActiveRenderInfo.rotationX, rz = ActiveRenderInfo.rotationZ;
        float ux = ActiveRenderInfo.rotationYZ, uy = ActiveRenderInfo.rotationXZ, uz = ActiveRenderInfo.rotationXY;
        int lastStep = -1;
        double lastX = Double.NaN, lastZ = Double.NaN;
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < tx.length / 4; i++) {
            double x = tx[i * 4] + 0.5, y = tx[i * 4 + 1] + 0.09, z = tx[i * 4 + 2] + 0.5;
            double dx = x - px, dy = y - py, dz = z - pz, dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > max) {
                continue;
            }
            if (Double.isNaN(lastX) || Math.abs(x - lastX) + Math.abs(z - lastZ) > 64) {
                lastStep = AirportClientSVA.taxiStep(x, z, night, rain);
                lastX = x;
                lastZ = z;
            }
            int kind = tx[i * 4 + 3], step = lastStep, colour;
            Integer key = Integer.valueOf(i);
            if (kind == com.sva.block.BlockTaxiLightSVA.CENTRE) {
                if (same && lit.follow) {
                    step = lit.lit.contains(key) ? Math.max(4, step) : 0;
                }
                colour = RunwayLayout.GREEN;
            } else if (kind == com.sva.block.BlockTaxiLightSVA.EDGE) {
                colour = RunwayLayout.BLUE;
            } else if (kind == com.sva.block.BlockTaxiLightSVA.STAND) {
                colour = RunwayLayout.YELLOW;
            } else {
                step = same && lit.stops.contains(key) ? Math.max(4, step) : 0;
                colour = RunwayLayout.RED;
            }
            if (step <= 0) {
                continue;
            }
            float[] rgb = rgb(colour);
            double k = RunwayLayout.LEVEL[Math.min(RunwayLayout.STEPS, step)];
            GL11.glColor4f(rgb[0], rgb[1], rgb[2], (float) Math.min(1, 0.35 + 0.65 * k));
            float s = (float) (RunwayLayout.spriteSize(dist, step) * 0.8);
            if (kind == com.sva.block.BlockTaxiLightSVA.STOP) {
                for (int q = -2; q <= 2; q++) {
                    sprite((float) (x + stopAcross[i * 2] * q * 0.4), (float) y, (float) (z + stopAcross[i * 2 + 1] * q * 0.4), s, rx, rz, ux, uy, uz);
                }
            } else {
                sprite((float) x, (float) y, (float) z, s, rx, rz, ux, uy, uz);
            }
        }
        GL11.glEnd();
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
    }

    static void sprite(float x, float y, float z, float s, float rx, float rz, float ux, float uy, float uz) {
        GL11.glTexCoord2f(0F, 0F);
        GL11.glVertex3f(x - rx * s - ux * s, y - uy * s, z - rz * s - uz * s);
        GL11.glTexCoord2f(0F, 1F);
        GL11.glVertex3f(x - rx * s + ux * s, y + uy * s, z - rz * s + uz * s);
        GL11.glTexCoord2f(1F, 1F);
        GL11.glVertex3f(x + rx * s + ux * s, y + uy * s, z + rz * s + uz * s);
        GL11.glTexCoord2f(1F, 0F);
        GL11.glVertex3f(x + rx * s - ux * s, y - uy * s, z + rz * s - uz * s);
    }

    /** A world left: the display lists go (their GL ids would leak). */
    public void clear() {
        for (long[] l : lists.values()) {
            GLAllocation.deleteDisplayLists((int) l[0]);
        }
        lists.clear();
        lightCache.clear();
    }
}
