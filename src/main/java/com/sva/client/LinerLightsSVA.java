package com.sva.client;

import java.nio.FloatBuffer;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.AnimMath;
import com.sva.vehicle.LinerLights;
import com.sva.vehicle.LinerModelData;
import com.sva.vehicle.LinerSystems;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Liner wave 6 (mock-up veh_liner_5): the SA-320's exterior lights as every client sees them - from the synced
 * LinerLights words (what shines; the strobes / beacons flash by the world time + a per-aircraft phase, no packets):
 * <ul>
 * <li>halos (config vehicles.linerExtLights): a bright core + a soft camera-facing halo at each lamp (LinerLights.LAMPS -
 * the landing lights where their housings have swung, the nose gear's lamps on its leg); the strobes, the beacons and the
 * nav lights keep a least apparent size (a fraction of their distance) - so an aircraft at night is seen from afar
 * whatever the model's LOD; in the own first-person view their size is clamped (CockpitView.ownGlow);</li>
 * <li>beams: additive cones out of the landing, take-off / taxi, runway turn-off and wing lights, only in the dark;</li>
 * <li>light pools (config vehicles.linerGroundPools): where each beam meets the ground (a cheap ray march through the
 * blocks) a soft elliptic decal on the block tops, stretched by the grazing angle;</li>
 * <li>the logo light's wash over the fin (LinerModelSVA: the fin's livery drawn again additive).</li>
 * </ul>
 * The real block light under the landing / taxi lights (config vehicles.linerDynamicLight) is DynamicLightSVA's (it asks
 * cells() here). Fixed-function GL, additive blending, no depth writes - OptiFine-safe as VehicleLightsSVA.
 */
@SideOnly(Side.CLIENT)
public final class LinerLightsSVA {

    private static final FloatBuffer MV = BufferUtils.createFloatBuffer(16);
    private static final int SEG = 16;
    /** A flashing / nav lamp's least radius as a share of its distance (~0.25 deg: a point of light far away). */
    static final float FAR_K = 0.0045F;

    private LinerLightsSVA() {
    }

    /** A lamp's centre in the model frame for this pose (the landing lights' housings, the nose gear's leg). */
    static float[] lampPos(LinerLights.Lamp l, LinerModelData.Pose pose) {
        String g = l.kind == LinerLights.K_LAND ? (l.side < 0 ? "landlight_L" : "landlight_R")
                : l.kind == LinerLights.K_TAXI || l.kind == LinerLights.K_TO || l.kind == LinerLights.K_RTO ? "nlg" : null;
        Map<String, LinerModelData.Joint> rig = LinerModelSVA.rig();
        if (g == null || rig == null || pose == null) {
            return new float[] {l.x, l.y, l.z};
        }
        float[] m = LinerModelData.world(rig, g, pose);
        return new float[] {m[0] * l.x + m[1] * l.y + m[2] * l.z + m[3], m[4] * l.x + m[5] * l.y + m[6] * l.z + m[7],
                m[8] * l.x + m[9] * l.y + m[10] * l.z + m[11]};
    }

    /** The halo's radius (blocks) and its core's for a lamp kind. */
    static float[] size(int kind) {
        switch (kind) {
            case LinerLights.K_STROBE:
                return new float[] {1.4F, 0.12F};
            case LinerLights.K_BEACON:
                return new float[] {1.0F, 0.10F};
            case LinerLights.K_LAND:
            case LinerLights.K_TO:
                return new float[] {1.3F, 0.14F};
            case LinerLights.K_TAXI:
            case LinerLights.K_RTO:
                return new float[] {0.8F, 0.09F};
            case LinerLights.K_LOGO:
            case LinerLights.K_WING:
                return new float[] {0.45F, 0.06F};
            default:
                return new float[] {0.55F, 0.07F};                  // the nav lights
        }
    }

    /**
     * The model frame is set (LinerModelSVA: the attitude applied): the beams, then the halos. ownView - the local pilot's /
     * passenger's own first-person view.
     */
    static void render(EntityVehicleSVA v, LinerSystems L, LinerModelData.Pose pose, boolean ownView, float pt) {
        if (!ConfigSVA.linerExtLights || L == null || !v.powered() || L.lights.ext == 0) {
            return;
        }
        int ext = L.lights.ext;
        float dark = VehicleLightsSVA.darkness(v);
        double time = v.worldObj.getTotalWorldTime() + pt;
        long tick = v.worldObj.getTotalWorldTime();
        int phase = AnimMath.phase(v.getEntityId());
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_CURRENT_BIT);
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        try {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            GL11.glDepthMask(false);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            if (dark > 0.05F) {
                for (LinerLights.Lamp l : LinerLights.LAMPS) {
                    float[] b = LinerLights.beam(l);
                    if (b != null && LinerLights.lit(l, ext)) {
                        cone(lampPos(l, pose), b, dark);
                    }
                }
            }
            MV.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MV);
            float day = 0.35F + 0.65F * dark;
            for (LinerLights.Lamp l : LinerLights.LAMPS) {
                if (!LinerLights.lit(l, ext)) {
                    continue;
                }
                float a;
                float grow = 1F;
                switch (l.kind) {
                    case LinerLights.K_STROBE:
                        if (!LinerLights.strobeAt(tick, phase)) {
                            continue;
                        }
                        a = 1F;
                        break;
                    case LinerLights.K_BEACON: {
                        float k = LinerLights.beaconAt(time + phase, l.y < 2F);
                        if (k < 0.03F) {
                            continue;
                        }
                        a = k * (0.45F + 0.55F * day);
                        grow = 0.4F + 0.6F * k;
                        break;
                    }
                    case LinerLights.K_LAND:
                    case LinerLights.K_TO:
                        a = 0.35F + 0.55F * dark;
                        break;
                    case LinerLights.K_TAXI:
                    case LinerLights.K_RTO:
                        a = 0.25F + 0.45F * dark;
                        break;
                    case LinerLights.K_LOGO:
                    case LinerLights.K_WING:
                        a = 0.15F + 0.4F * dark;
                        break;
                    default:
                        a = 0.55F * day;
                        break;
                }
                float[] s = size(l.kind);
                boolean far = l.kind == LinerLights.K_STROBE || l.kind == LinerLights.K_BEACON || l.kind <= LinerLights.K_NAV_W;
                glow(lampPos(l, pose), LinerLights.colour(l.kind), s[0] * grow, a, s[1], ownView, far);
            }
        } finally {
            GL11.glDepthMask(true);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
            GL11.glPopAttrib();
            GL11.glColor4f(1F, 1F, 1F, 1F);
        }
    }

    /** A light cone from p along b (LinerLights.beam: dir, length, end radius, strength) - two layers, fading to nothing. */
    private static void cone(float[] p, float[] b, float dark) {
        float dx = b[0], dy = b[1], dz = b[2], len = b[3];
        // two unit vectors across the beam
        float[] u = norm(cross(new float[] {dx, dy, dz}, Math.abs(dy) > 0.9F ? new float[] {1, 0, 0} : new float[] {0, 1, 0}));
        float[] w = cross(new float[] {dx, dy, dz}, u);
        for (int layer = 0; layer < 2; layer++) {
            float r1 = layer == 0 ? b[4] : b[4] * 0.45F, r0 = 0.08F;
            float a0 = 0.24F * dark * b[5] * (layer == 0 ? 0.6F : 0.85F);
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            for (int k = 0; k <= SEG; k++) {
                double ang = k * Math.PI * 2 / SEG;
                float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
                float ox = u[0] * c + w[0] * s, oy = u[1] * c + w[1] * s, oz = u[2] * c + w[2] * s;
                GL11.glColor4f(1F, 0.95F, 0.84F, a0);
                GL11.glVertex3f(p[0] + ox * r0, p[1] + oy * r0, p[2] + oz * r0);
                GL11.glColor4f(1F, 0.95F, 0.84F, 0F);
                GL11.glVertex3f(p[0] + dx * len + ox * r1, p[1] + dy * len + oy * r1, p[2] + dz * len + oz * r1);
            }
            GL11.glEnd();
        }
    }

    static float[] cross(float[] a, float[] b) {
        return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static float[] norm(float[] a) {
        float l = (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return l < 1e-6F ? new float[] {0, 1, 0} : new float[] {a[0] / l, a[1] / l, a[2] / l};
    }

    /**
     * A camera-facing glow at p (model frame): its eye-space position from the model-view matrix, pulled a little toward the
     * camera (the hull does not cut it); far - a least radius of FAR_K x the distance (a point of light at any range).
     */
    private static void glow(float[] p, int rgb, float size, float alpha, float core, boolean own, boolean far) {
        if (alpha <= 0.01F) {
            return;
        }
        float ex = MV.get(0) * p[0] + MV.get(4) * p[1] + MV.get(8) * p[2] + MV.get(12);
        float ey = MV.get(1) * p[0] + MV.get(5) * p[1] + MV.get(9) * p[2] + MV.get(13);
        float ez = MV.get(2) * p[0] + MV.get(6) * p[1] + MV.get(10) * p[2] + MV.get(14);
        float d = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (own) {
            float k = (float) (com.sva.vehicle.CockpitView.ownGlow(size, d) / size);
            size *= k;
            core *= k;
        } else {
            if (far && d * FAR_K > size) {
                core *= d * FAR_K / size;
                size = d * FAR_K;
            }
            if (d > 0.6F) {
                float f = (d - 0.3F) / d;
                ex *= f;
                ey *= f;
                ez *= f;
            }
        }
        float r = (rgb >> 16 & 255) / 255F, g = (rgb >> 8 & 255) / 255F, b = (rgb & 255) / 255F;
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(ex, ey, ez);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(r, g, b, Math.min(1F, alpha));
        GL11.glVertex3f(0F, 0F, 0F);
        GL11.glColor4f(r, g, b, 0F);
        for (int k = 0; k <= SEG; k++) {
            double a = k * Math.PI * 2 / SEG;
            GL11.glVertex3f((float) Math.cos(a) * size, (float) Math.sin(a) * size, 0F);
        }
        GL11.glEnd();
        if (core > 0F) {
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            GL11.glColor4f(Math.min(1F, r + 0.5F), Math.min(1F, g + 0.5F), Math.min(1F, b + 0.5F), Math.min(1F, alpha + 0.3F));
            GL11.glVertex3f(0F, 0F, 0F);
            GL11.glColor4f(r, g, b, 0.8F * Math.min(1F, alpha + 0.3F));
            for (int k = 0; k <= 8; k++) {
                double a = k * Math.PI * 2 / 8;
                GL11.glVertex3f((float) Math.cos(a) * core, (float) Math.sin(a) * core, 0F);
            }
            GL11.glEnd();
        }
        GL11.glPopMatrix();
    }

    // ---------------------------------------------------------------- the world: the lamps' places, the pools

    /**
     * A model-frame vector into the world (offset from the entity, blocks): the attitude about ATTITUDE_Y (roll, then pitch)
     * as LinerModelSVA draws it, then the yaw; point - with the attitude's pivot (a direction without it).
     */
    static double[] toWorld(float[] p, float yaw, float pitch, float roll, boolean point) {
        double x = p[0], y = p[1] - (point ? LinerModelSVA.ATTITUDE_Y : 0), z = p[2];
        double ra = Math.toRadians(roll), pa = Math.toRadians(pitch), ya = Math.toRadians(-yaw - 90);
        double y1 = y * Math.cos(ra) - z * Math.sin(ra), z1 = y * Math.sin(ra) + z * Math.cos(ra);
        double x2 = x * Math.cos(pa) - y1 * Math.sin(pa), y2 = x * Math.sin(pa) + y1 * Math.cos(pa);
        y2 += point ? LinerModelSVA.ATTITUDE_Y : 0;
        double x3 = x2 * Math.cos(ya) + z1 * Math.sin(ya), z3 = -x2 * Math.sin(ya) + z1 * Math.cos(ya);
        return new double[] {x3, y2, z3};
    }

    /** A beamed lamp lit now: {origin (world offset from the entity) x y z, direction x y z, length, end radius, strength}. */
    static double[] worldBeam(EntityVehicleSVA v, LinerLights.Lamp l, LinerModelData.Pose pose, float pt) {
        float[] b = LinerLights.beam(l);
        if (b == null) {
            return null;
        }
        float yaw = v.prevRotationYaw + MathHelper.wrapAngleTo180_float(v.rotationYaw - v.prevRotationYaw) * pt;
        float pitch = v.prevRenderPitch + (v.renderPitch - v.prevRenderPitch) * pt;
        float roll = v.prevRenderRoll + (v.renderRoll - v.prevRenderRoll) * pt;
        double[] o = toWorld(lampPos(l, pose), yaw, pitch, roll, true);
        double[] d = toWorld(new float[] {b[0], b[1], b[2]}, yaw, pitch, roll, false);
        return new double[] {o[0], o[1], o[2], d[0], d[1], d[2], b[3], b[4], b[5]};
    }

    /** The ground under a beam: the first solid block along it (from 0.5 blocks out) - {distance, x, top y, z} or null. */
    static double[] hit(World w, double ox, double oy, double oz, double dx, double dy, double dz, double len) {
        for (double s = 0.5; s <= len; s += 0.5) {
            double px = ox + dx * s, py = oy + dy * s, pz = oz + dz * s;
            int bx = MathHelper.floor_double(px), by = MathHelper.floor_double(py), bz = MathHelper.floor_double(pz);
            if (by < 0 || by > 255 || !w.blockExists(bx, by, bz)) {
                return null;
            }
            Block blk = w.getBlock(bx, by, bz);
            if (blk.getMaterial().isSolid() || blk.getMaterial().isLiquid()) {
                return new double[] {s, px, by + blk.getBlockBoundsMaxY(), pz};
            }
        }
        return null;
    }

    /**
     * The light pools under the beams (config vehicles.linerGroundPools), drawn in a world-aligned frame at the entity's
     * render position (x, y, z): a soft ellipse on the block tops where each lit beam meets the ground, along the beam's
     * ground track, stretched by the grazing angle, dimmer with distance - only in the dark.
     */
    static void pools(EntityVehicleSVA v, LinerSystems L, LinerModelData.Pose pose, double x, double y, double z, float pt) {
        if (!ConfigSVA.linerExtLights || !ConfigSVA.linerGroundPools || L == null || !v.powered() || L.lights.ext == 0) {
            return;
        }
        float dark = VehicleLightsSVA.darkness(v);
        if (dark < 0.05F) {
            return;
        }
        double ex = v.prevPosX + (v.posX - v.prevPosX) * pt, ey = v.prevPosY + (v.posY - v.prevPosY) * pt, ez = v.prevPosZ + (v.posZ - v.prevPosZ) * pt;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_POLYGON_BIT);
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        try {
            GL11.glTranslated(x, y, z);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(-2F, -4F);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            for (LinerLights.Lamp l : LinerLights.LAMPS) {
                if (!LinerLights.lit(l, L.lights.ext) || l.kind == LinerLights.K_WING) {
                    continue;
                }
                double[] b = worldBeam(v, l, pose, pt);
                if (b == null) {
                    continue;
                }
                double[] h = hit(v.worldObj, ex + b[0], ey + b[1], ez + b[2], b[3], b[4], b[5], b[6]);
                if (h == null) {
                    continue;
                }
                double s = h[0], fade = 1 - s / b[6];
                double r = 0.4 + b[7] * s / b[6] * 1.2;
                double hor = Math.sqrt(b[3] * b[3] + b[5] * b[5]);
                double stretch = Math.min(3, hor / Math.max(0.15, Math.abs(b[4])) * 0.35 + 1);
                double fx = hor > 1e-3 ? b[3] / hor : 1, fz = hor > 1e-3 ? b[5] / hor : 0;
                float a = (float) (0.42 * dark * b[8] * Math.sqrt(Math.max(0, fade)));
                ellipse(h[1] - ex, h[2] + 0.02 - ey, h[3] - ez, fx, fz, r * stretch, r, a);
            }
        } finally {
            GL11.glPolygonOffset(0F, 0F);
            GL11.glDepthMask(true);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
            GL11.glPopAttrib();
            GL11.glPopMatrix();
            GL11.glColor4f(1F, 1F, 1F, 1F);
        }
    }

    /** A soft flat ellipse at (cx, cy, cz), its long axis (ra) along (fx, fz), the short (rb) across. */
    private static void ellipse(double cx, double cy, double cz, double fx, double fz, double ra, double rb, float a) {
        if (a < 0.01F) {
            return;
        }
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(1F, 0.93F, 0.80F, a);
        GL11.glVertex3d(cx, cy, cz);
        GL11.glColor4f(1F, 0.93F, 0.80F, 0F);
        for (int k = 0; k <= 20; k++) {
            double t = k * Math.PI * 2 / 20, c = Math.cos(t) * ra, s = Math.sin(t) * rb;
            GL11.glVertex3d(cx + fx * c - fz * s, cy, cz + fz * c + fx * s);
        }
        GL11.glEnd();
    }

    /**
     * DynamicLightSVA's liner branch (config vehicles.linerDynamicLight): the air blocks along each lit landing / take-off /
     * taxi / turn-off beam get block light (15 near the lamp, dimmer out to the beam's end, one block to each side) - the
     * same trick as the vehicles' headlights; only in the dark.
     */
    static void cells(World w, EntityVehicleSVA v, Map<Long, Integer> want) {
        LinerSystems L = v.liner();
        if (L == null || !ConfigSVA.linerDynamicLight || VehicleLightsSVA.darkness(v) < 0.3F) {
            return;
        }
        LinerModelData.Pose pose = new LinerModelData.Pose();
        pose.set("landlight_L", LinerLights.landAngle(L.lights.landL), 0).set("landlight_R", LinerLights.landAngle(L.lights.landR), 0);
        for (LinerLights.Lamp l : LinerLights.LAMPS) {
            if (!LinerLights.lit(l, L.lights.ext) || l.kind == LinerLights.K_WING) {
                continue;
            }
            double[] b = worldBeam(v, l, pose, 1F);
            if (b == null) {
                continue;
            }
            double ox = v.posX + b[0], oy = v.posY + b[1], oz = v.posZ + b[2];
            double sx = -b[5], sz = b[3];
            double sl = Math.sqrt(sx * sx + sz * sz);
            if (sl > 1e-6) {
                sx /= sl;
                sz /= sl;
            }
            int reach = (int) Math.min(18, b[6] * 0.6);
            for (int d = 1; d <= reach; d++) {
                int bx = MathHelper.floor_double(ox + b[3] * d), by = MathHelper.floor_double(oy + b[4] * d), bz = MathHelper.floor_double(oz + b[5] * d);
                if (by < 1 || by > 254 || !w.blockExists(bx, by, bz) || w.getBlock(bx, by, bz).isOpaqueCube()) {
                    break;
                }
                int level = 15 - (int) (d * 0.5F);
                DynamicLightSVA.put(w, want, bx, by, bz, level);
                for (int s = -1; s <= 1; s += 2) {
                    DynamicLightSVA.put(w, want, MathHelper.floor_double(ox + b[3] * d + sx * s), by, MathHelper.floor_double(oz + b[5] * d + sz * s), level - 2);
                }
            }
        }
    }
}
