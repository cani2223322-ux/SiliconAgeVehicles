package com.sva.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.sva.ConfigSVA;
import com.sva.airport.EmbassyLightsMath;
import com.sva.airport.RunwayLayout;
import com.sva.airport.WindField;
import com.sva.block.AirportDataSVA;
import com.sva.vehicle.CruiseMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.world.World;

/**
 * «Лётное поле посольства» (docs/plan-embassy-airfield-addon.md §3.3): what the add-on adds to an embassy's field besides its runway's
 * lights (AirportRenderSVA with EmbassyLightsMath) - nothing of it a block (the add-on places none at an embassy), all from the
 * server's per-player copy (AirportClientSVA.State.embPapi / embSock: only the embassies this player knows), drawn from
 * AirportRenderSVA's RenderWorldLastEvent pass (world coordinates, its GL state pushed):
 * <ul>
 * <li><b>PAPI</b>: four boxes beside each runway end (EmbassyLightsMath.papiBoxes from the server's point; matched to its virtual
 * runway end), a dark housing each near by (HOUSING_RANGE), a lens glow toward the approach only - white or red by the viewer's
 * elevation over the row (EmbassyLightsMath.papiWhites: on the slope two white outside, two red inside); the end's runway step (its
 * mode, the tower's «ВЫШКА», AUTO), by a clear day in AUTO still PAPI_DAY_STEP.</li>
 * <li><b>The windsock</b>: on the core's mast (a fence and an orange wool cone over its foot) a swivel and the orange / white sock
 * blowing downwind, lifted by the wind and fluttering - airport.WindField from the synced seed, as the add-on's own «Ветроуказатель»
 * (RenderAptSVA); only while the cone's block stands (a chunk not built yet - nothing).</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
final class EmbassyRenderSVA {

    /** The housings and the socks are drawn this near (blocks); a PAPI matches its runway end within these (along / across). */
    private static final double HOUSING_RANGE = 96, SOCK_RANGE = 128, MATCH_ALONG = 30, MATCH_ACROSS = 30, MATCH_HEADING = 30;
    /** The sock's swivel: its post over the cone, its arm downwind (clear of the cone block's corners), the sock's scale. */
    private static final float POST = 0.45F, ARM = 0.75F, SOCK_SCALE = 1.25F;

    /** One PAPI as drawn: its boxes {x, z}, the ground under them, the landing direction, its glide angle, its end (null: unmatched). */
    private static final class Papi {
        final double[] x = new double[4], z = new double[4];
        double ground, dx, dz, angle, mx, mz;
        float yaw;
        AirportClientSVA.Rwy rw;
        int dir;
    }

    private static List<Papi> papis = Collections.<Papi>emptyList();
    private static int papisFor = -1;

    private EmbassyRenderSVA() {
    }

    /** The whole pass (AirportRenderSVA.onRenderLast, inside its pushed state and world translation). */
    static void draw(World w, AirportClientSVA.State st, double px, double py, double pz, double time, float pt, boolean night, boolean rain) {
        if (st.embPapi.length > 0 && ConfigSVA.aptLights) {
            if (papisFor != st.version) {
                papis = resolve(st);
                papisFor = st.version;
            }
            papi(w, px, py, pz, pt, night, rain);
        }
        if (st.embSock.length > 0) {
            socks(w, st, px, py, pz, time, pt);
        }
    }

    /** A world left: the matched PAPI go. */
    static void clear() {
        papis = Collections.<Papi>emptyList();
        papisFor = -1;
    }

    // ---------------------------------------------------------------- PAPI

    /** Each EmbPapi point as its four boxes, matched to the virtual runway end it serves (the nearest by place and heading). */
    private static List<Papi> resolve(AirportClientSVA.State st) {
        List<Papi> out = new ArrayList<Papi>();
        int[] a = st.embPapi;
        for (int i = 0; i + AirportClientSVA.PAPI_INTS <= a.length; i += AirportClientSVA.PAPI_INTS) {
            double cx = a[i] + 0.5, cz = a[i + 2] + 0.5, hdg = a[i + 3] / 10.0;
            Papi p = new Papi();
            p.angle = EmbassyLightsMath.angleOf(a[i + 4]);
            double best = Double.MAX_VALUE;
            for (int pass = 0; pass < 2 && p.rw == null; pass++) {     // by place and heading; by place alone
                for (AirportClientSVA.Rwy rw : st.runways) {
                    if (rw.emb < 0) {
                        continue;
                    }
                    for (int dir = -1; dir <= 1; dir += 2) {
                        double[] l = rw.r.local(dir, cx, cz);
                        if (l[0] < -MATCH_ALONG || l[0] > rw.r.len / 2 || Math.abs(l[1]) > MATCH_ACROSS
                                || pass == 0 && Math.abs(CruiseMath.wrap(rw.r.course(dir) - hdg)) > MATCH_HEADING) {
                            continue;
                        }
                        double d = l[0] * l[0] + l[1] * l[1];
                        if (d < best) {
                            best = d;
                            p.rw = rw;
                            p.dir = dir;
                        }
                    }
                }
            }
            double c;
            if (p.rw != null) {
                double[] l = p.rw.r.local(p.dir, cx, cz);
                double[][] b = EmbassyLightsMath.papiBoxes(l[0], l[1], p.rw.r.width);
                for (int k = 0; k < 4; k++) {
                    double[] wp = p.rw.r.world(p.dir, b[k][0], b[k][1]);
                    p.x[k] = wp[0];
                    p.z[k] = wp[1];
                }
                p.ground = p.rw.r.elev;
                c = Math.toRadians(p.rw.r.course(p.dir));
            } else {
                // no known runway end - the point as given, the boxes out to the left of the landing
                c = Math.toRadians(hdg);
                for (int k = 0; k < 4; k++) {
                    p.x[k] = cx - Math.cos(c) * k * EmbassyLightsMath.PAPI_SPACING;
                    p.z[k] = cz - Math.sin(c) * k * EmbassyLightsMath.PAPI_SPACING;
                }
                p.ground = a[i + 1] + 1;
            }
            p.dx = Math.sin(c);
            p.dz = -Math.cos(c);
            p.mx = (p.x[1] + p.x[2]) / 2;
            p.mz = (p.z[1] + p.z[2]) / 2;
            p.yaw = (float) (-90 - Math.toDegrees(c));                // local +x toward the approach
            out.add(p);
        }
        return out;
    }

    private static void papi(World w, double px, double py, double pz, float pt, boolean night, boolean rain) {
        Minecraft mc = Minecraft.getMinecraft();
        // the housings (shaded boxes in the world's light), near only
        mc.entityRenderer.enableLightmap(pt);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        for (Papi p : papis) {
            double hx = p.mx - px, hz = p.mz - pz;
            if (hx * hx + hz * hz > HOUSING_RANGE * HOUSING_RANGE) {
                continue;
            }
            int bx = (int) Math.floor(p.mx), by = (int) Math.floor(p.ground), bz = (int) Math.floor(p.mz);
            if (!w.blockExists(bx, by, bz)) {
                continue;
            }
            int b = w.getLightBrightnessForSkyBlocks(bx, by, bz, 0);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, b & 0xFFFF, b >> 16);
            for (int k = 0; k < 4; k++) {
                GL11.glPushMatrix();
                GL11.glTranslated(p.x[k], p.ground, p.z[k]);
                GL11.glRotatef(p.yaw, 0F, 1F, 0F);
                for (int leg = 0; leg < 4; leg++) {
                    float lx = leg < 2 ? -0.18F : 0.06F, lz = (leg & 1) == 0 ? -0.24F : 0.2F;
                    RenderAptSVA.box(lx, 0F, lz, lx + 0.04F, 0.22F, lz + 0.04F, RenderAptSVA.METAL);
                }
                RenderAptSVA.box(-0.22F, 0.22F, -0.32F, 0.14F, 0.6F, 0.32F, RenderAptSVA.DARK);
                RenderAptSVA.box(0.14F, 0.56F, -0.32F, 0.22F, 0.6F, 0.32F, RenderAptSVA.DARK);
                GL11.glPopMatrix();
            }
        }
        mc.entityRenderer.disableLightmap(pt);
        // the lenses: camera-facing glows toward the approach, white / red by the viewer's elevation over the row
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, AirportRenderSVA.glow());
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
        GL11.glBegin(GL11.GL_QUADS);
        for (Papi p : papis) {
            double lensY = p.ground + EmbassyLightsMath.PAPI_H;
            double ex = px - p.mx, ez = pz - p.mz, flat = Math.sqrt(ex * ex + ez * ez);
            if (flat > ConfigSVA.aptDrawDistance || ex * p.dx + ez * p.dz > -0.3) {
                continue;                                           // too far, or beside / behind the boxes (they face the approach)
            }
            int mode = p.rw == null ? RunwayLayout.LIGHTS_AUTO : p.rw.r.end(p.dir).lights;
            int rstep = p.rw == null ? RunwayLayout.step(mode, night, rain, 10000) : AirportClientSVA.step(p.rw, p.dir, night, rain);
            int step = EmbassyLightsMath.papiStep(mode, rstep);
            if (step <= 0) {
                continue;
            }
            int whites = EmbassyLightsMath.papiWhites(EmbassyLightsMath.elevation(py - lensY, flat), p.angle);
            double k = RunwayLayout.LEVEL[Math.min(RunwayLayout.STEPS, step)];
            for (int i = 0; i < 4; i++) {
                float[] rgb = AirportRenderSVA.rgb(EmbassyLightsMath.papiWhite(i, whites) ? RunwayLayout.WHITE : RunwayLayout.RED);
                float x = (float) (p.x[i] - p.dx * 0.26), y = (float) lensY, z = (float) (p.z[i] - p.dz * 0.26);
                double dx = x - px, dy = y - py, dz = z - pz, dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                float s = (float) (RunwayLayout.spriteSize(dist, step) * 1.6);
                GL11.glColor4f(rgb[0], rgb[1], rgb[2], (float) Math.min(1, 0.45 + 0.55 * k));
                AirportRenderSVA.sprite(x, y, z, s, rx, rz, ux, uy, uz);
            }
        }
        GL11.glEnd();
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
    }

    // ---------------------------------------------------------------- the windsocks

    private static void socks(World w, AirportClientSVA.State st, double px, double py, double pz, double time, float pt) {
        Minecraft mc = Minecraft.getMinecraft();
        WindField.Wind wind = null;
        boolean on = false;
        int[] a = st.embSock;
        for (int i = 0; i + AirportClientSVA.SOCK_INTS <= a.length; i += AirportClientSVA.SOCK_INTS) {
            int x = a[i], y = a[i + 1], z = a[i + 2];
            double dx = x + 0.5 - px, dy = y + 3 - py, dz = z + 0.5 - pz;
            if (dx * dx + dy * dy + dz * dz > SOCK_RANGE * SOCK_RANGE || y < 1 || y > 250 || !w.blockExists(x, y, z)) {
                continue;
            }
            int top = mastTop(w, x, y, z);
            if (top < 0) {
                continue;                                           // the core's mast and cone not there (yet)
            }
            if (!on) {
                mc.entityRenderer.enableLightmap(pt);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDepthMask(true);
                wind = WindField.wind(st.windSeed, time, w.isRaining(), w.isThundering(), AirportDataSVA.WX_HEIGHT);
                on = true;
            }
            int b = w.getLightBrightnessForSkyBlocks(x, top, z, 0);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, b & 0xFFFF, b >> 16);
            GL11.glPushMatrix();
            GL11.glTranslated(x + 0.5, top, z + 0.5);               // the top of the core's cone block
            sock(wind, time, i);
            GL11.glPopMatrix();
        }
        if (on) {
            mc.entityRenderer.disableLightmap(pt);
        }
    }

    /** The first air over the mast standing on (x, y, z) - the cone's top (the core: a fence and the wool cone, H_WINDSOCK); -1 none. */
    private static int mastTop(World w, int x, int y, int z) {
        for (int yy = y + 1; yy <= y + 5; yy++) {
            if (w.isAirBlock(x, yy, z) && !w.isAirBlock(x, yy - 1, z)) {
                return yy - y >= 2 ? yy : -1;                       // at least two blocks of mast (not the bare ground)
            }
        }
        return -1;
    }

    /** The swivel and the sock (as RenderAptSVA's windsock, on an arm): local origin on the cone's top. */
    private static void sock(WindField.Wind wind, double time, int seed) {
        float to = (float) (wind.from + 180);
        RenderAptSVA.box(-0.03F, 0F, -0.03F, 0.03F, POST, 0.03F, RenderAptSVA.METAL);
        double lift = Math.min(1, wind.gust / 7.7);
        float droop = (float) (80 - 80 * lift + 4 * Math.sin(time * 0.9 + wind.gust + seed));
        GL11.glRotatef(-to + 90F, 0F, 1F, 0F);
        RenderAptSVA.box(0F, POST - 0.04F, -0.02F, ARM, POST, 0.02F, RenderAptSVA.METAL);
        GL11.glTranslatef(ARM, POST - 0.02F, 0F);
        GL11.glScalef(SOCK_SCALE, SOCK_SCALE, SOCK_SCALE);
        GL11.glRotatef(-droop, 0F, 0F, 1F);
        RenderAptSVA.box(-0.01F, -0.2F, -0.2F, 0.02F, 0.2F, 0.2F, RenderAptSVA.METAL);
        float x = 0.02F;
        for (int i = 0; i < 5; i++) {
            float r0 = 0.18F - i * 0.022F;
            float len = 0.2F;
            float wob = (float) (0.015 * Math.sin(time * 1.7 + i * 1.3 + seed) * (1 - lift * 0.5));
            RenderAptSVA.box(x, -r0 + wob, -r0, x + len, r0 + wob, r0, i % 2 == 0 ? RenderAptSVA.ORANGE : RenderAptSVA.WHITE);
            x += len;
        }
    }
}
