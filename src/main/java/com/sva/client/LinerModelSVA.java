package com.sva.client;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.sva.ConfigSVA;
import com.sva.ReferenceSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.AnimMath;
import com.sva.vehicle.HighLiftMath;
import com.sva.vehicle.LinerAnim;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerModelData;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.LinerTexMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

/**
 * Liner wave 1 (plan-next §7, mock-ups veh_liner_1..4): the SA-320's exterior. Its own pipeline beside ObjModelSVA (the
 * other vehicles' models stay untouched): the exported concept (LinerModelData - liner_lod0..2.obj, every moving part a
 * rig group with its hinge pivot and axis along the real swept hinge line) compiled into display lists per group (plain
 * colours / the livery texture / the two repeat patterns / each light material) and posed per frame:
 * <ul>
 * <li>the fans spin by N1; the gear's sequence by the gear lever (LinerAnim: doors open - legs fold, the mains inboard and
 * the nose forward - doors shut), the oleos extend in the air, the wheels roll, the nose wheel steers;</li>
 * <li>the surfaces from the old plane controls: ailerons, elevators, rudder (AnimMath's deflections), the roll spoilers on
 * the down-going wing, the speed brake / ground spoilers, the slats, the Fowler flaps (travel + turn) with their
 * fairings - all about the rig's real hinge axes; wave 2 adds the hydraulics and the drooping;</li>
 * <li>the L1 door and the airstairs, the APU inlet flap, the retractable landing lights;</li>
 * <li>the lights by the overhead's switches: nav / logo, strobes and beacons flashing, landing, taxi / T.O., runway turn-off,
 * wing - drawn unlit (full bright) when on, dull when off.</li>
 * </ul>
 * Levels of detail by distance (LinerTexMath.lod, config vehicles.linerLod). In the own first-person view the fuselage is
 * left out - the cockpit / cabin (LinerCockpitSVA) is the inside, the eye is clipped as RenderVehicleSVA does.
 */
@SideOnly(Side.CLIENT)
public final class LinerModelSVA {

    /** The attitude's pivot height (model units: the fuselage's axis). */
    static final float ATTITUDE_Y = 2.1F;
    /** Groups left out in the own view (the interior is the inside) and the stairs while stowed. */
    private static final java.util.Set<String> SHELL = new java.util.HashSet<String>(java.util.Arrays.asList("airframe", "details", "doorL1",
            "stairs", "nlgwell", "mlgwell_L", "mlgwell_R"));

    /** One group's lists. */
    private static final class GL {
        final String name;
        int plain, livery, spinner, cascade;
        final List<String> emitMat = new ArrayList<String>();
        final List<Integer> emitList = new ArrayList<Integer>();

        GL(String name) {
            this.name = name;
        }
    }

    private static Map<String, LinerModelData.Joint> rig;
    private static Map<String, LinerModelData.Material> mats;
    private static final LinerModelData.Model[] MODELS = new LinerModelData.Model[3];
    private static final List<List<GL>> LISTS = new ArrayList<List<GL>>();
    private static final Future<?>[] LOADING = new Future<?>[3];
    private static boolean failed;

    static {
        for (int i = 0; i < 3; i++) {
            LISTS.add(null);
        }
    }

    private LinerModelSVA() {
    }

    private static InputStream open(String file) throws java.io.IOException {
        return Minecraft.getMinecraft().getResourceManager().getResource(new ResourceLocation(ReferenceSVA.ASSETS, LinerModelData.DIR + file))
                .getInputStream();
    }

    /** The lists of a LOD, parsing it off the render thread the first time (null while it loads). */
    private static List<GL> lists(final int lod) {
        if (failed) {
            return null;
        }
        if (LISTS.get(lod) != null) {
            return LISTS.get(lod);
        }
        try {
            if (rig == null) {
                rig = LinerModelData.parseRig(open(LinerModelData.RIG));
                mats = LinerModelData.parseMaterials(open(LinerModelData.MATS));
            }
            if (LOADING[lod] == null) {
                final InputStream in = open(LinerModelData.objName(lod));
                LOADING[lod] = HiResSVA.submit(new Callable<LinerModelData.Model>() {
                    @Override
                    public LinerModelData.Model call() throws Exception {
                        return LinerModelData.parse(in);
                    }
                });
                return null;
            }
            if (!LOADING[lod].isDone()) {
                return null;
            }
            MODELS[lod] = (LinerModelData.Model) LOADING[lod].get();
            LISTS.set(lod, compile(MODELS[lod]));
            MODELS[lod] = null;
        } catch (Throwable t) {
            failed = true;
            cpw.mods.fml.common.FMLLog.log(org.apache.logging.log4j.Level.WARN, t, "[SVA] liner model failed to load");
            return null;
        }
        return LISTS.get(lod);
    }

    /** Some LOD ready to draw: the wanted one, else the lightest that is loaded (the first frames). */
    private static List<GL> best(int lod) {
        List<GL> l = lists(lod);
        if (l != null) {
            return l;
        }
        for (int i = 2; i >= 0; i--) {
            if (LISTS.get(i) != null) {
                return LISTS.get(i);
            }
        }
        return lists(2);
    }

    private static List<GL> compile(LinerModelData.Model m) {
        List<GL> out = new ArrayList<GL>();
        float[] uv = new float[2];
        for (LinerModelData.Group g : m.groups.values()) {
            GL gl = new GL(g.name);
            // plain colours
            boolean any = false;
            for (LinerModelData.Mat mt : g.mats) {
                LinerModelData.Material ma = mats.get(mt.name);
                any |= !LinerModelData.livery(mt.name) && !LinerModelData.pattern(mt.name) && (ma == null || !ma.emissive);
            }
            if (any) {
                gl.plain = GL11.glGenLists(1);
                GL11.glNewList(gl.plain, GL11.GL_COMPILE);
                GL11.glBegin(GL11.GL_TRIANGLES);
                for (LinerModelData.Mat mt : g.mats) {
                    LinerModelData.Material ma = mats.get(mt.name);
                    if (LinerModelData.livery(mt.name) || LinerModelData.pattern(mt.name) || ma != null && ma.emissive) {
                        continue;
                    }
                    int c = ma == null ? 0xC0C0C0 : ma.rgb;
                    GL11.glColor3f((c >> 16 & 255) / 255F, (c >> 8 & 255) / 255F, (c & 255) / 255F);
                    verts(mt.v, null, null);
                }
                GL11.glEnd();
                GL11.glEndList();
            }
            for (LinerModelData.Mat mt : g.mats) {
                LinerModelData.Material ma = mats.get(mt.name);
                if (LinerModelData.livery(mt.name)) {
                    if (gl.livery == 0) {
                        gl.livery = GL11.glGenLists(1);
                        GL11.glNewList(gl.livery, GL11.GL_COMPILE);
                        GL11.glBegin(GL11.GL_TRIANGLES);
                        verts(mt.v, mt.name, uv);
                        GL11.glEnd();
                        GL11.glEndList();
                    } else {
                        // a second livery material in the group: its own list folded in by a new one wrapping both
                        int a = gl.livery, b = GL11.glGenLists(1);
                        GL11.glNewList(b, GL11.GL_COMPILE);
                        GL11.glBegin(GL11.GL_TRIANGLES);
                        verts(mt.v, mt.name, uv);
                        GL11.glEnd();
                        GL11.glEndList();
                        int c = GL11.glGenLists(1);
                        GL11.glNewList(c, GL11.GL_COMPILE);
                        GL11.glCallList(a);
                        GL11.glCallList(b);
                        GL11.glEndList();
                        gl.livery = c;
                    }
                } else if (LinerModelData.pattern(mt.name)) {
                    int l = GL11.glGenLists(1);
                    GL11.glNewList(l, GL11.GL_COMPILE);
                    GL11.glBegin(GL11.GL_TRIANGLES);
                    verts(mt.v, mt.name, uv);
                    GL11.glEnd();
                    GL11.glEndList();
                    if ("spinner".equals(mt.name)) {
                        gl.spinner = l;
                    } else {
                        gl.cascade = l;
                    }
                } else if (ma != null && ma.emissive) {
                    int l = GL11.glGenLists(1);
                    GL11.glNewList(l, GL11.GL_COMPILE);
                    GL11.glBegin(GL11.GL_TRIANGLES);
                    verts(mt.v, null, null);
                    GL11.glEnd();
                    GL11.glEndList();
                    gl.emitMat.add(mt.name);
                    gl.emitList.add(Integer.valueOf(l));
                }
            }
            out.add(gl);
        }
        return out;
    }

    private static void verts(float[] v, String uvMat, float[] uv) {
        boolean pat = uvMat != null && LinerModelData.pattern(uvMat);
        for (int t = 0; t + 18 <= v.length; t += 18) {
            float cz = (v[t + 2] + v[t + 8] + v[t + 14]) / 3;
            float ref = pat ? LinerModelData.patternRef(uvMat, v[t], v[t + 1], v[t + 2]) : 0;
            for (int k = 0; k < 3; k++) {
                int o = t + k * 6;
                if (uvMat != null) {
                    if (pat) {
                        LinerModelData.patternUv(uvMat, v[o], v[o + 1], v[o + 2], ref, uv);
                    } else {
                        LinerModelData.uv(uvMat, v[o], v[o + 1], v[o + 2], cz, uv);
                    }
                    GL11.glTexCoord2f(uv[0], uv[1]);
                }
                GL11.glNormal3f(v[o + 3], v[o + 4], v[o + 5]);
                GL11.glVertex3f(v[o], v[o + 1], v[o + 2]);
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    /** RenderVehicleSVA hands the liner over here (the entity's frame at x, y, z). */
    public static void render(EntityVehicleSVA v, double x, double y, double z, float pt) {
        Minecraft mc = Minecraft.getMinecraft();
        boolean ownView = mc.gameSettings.thirdPersonView == 0 && HudVehicleSVA.vehicleOf(mc.thePlayer) == v;
        double dist = Math.sqrt(x * x + y * y + z * z);
        LinerAnim la = v.linerAnim;
        int lod = ConfigSVA.linerLod && !ownView ? LinerTexMath.lod(dist, la.lod) : 0;
        la.lod = lod;
        List<GL> lists = best(lod);
        float ry = v.prevRotationYaw + MathHelper.wrapAngleTo180_float(v.rotationYaw - v.prevRotationYaw) * pt;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_TRANSFORM_BIT);
        try {
            GL11.glTranslated(x, y, z);
            GL11.glRotatef(-ry - 90F, 0F, 1F, 0F);
            if (ownView) {
                eyeClip(true);
            } else {
                float pitch = v.prevRenderPitch + (v.renderPitch - v.prevRenderPitch) * pt;
                float roll = v.prevRenderRoll + (v.renderRoll - v.prevRenderRoll) * pt;
                GL11.glTranslatef(0F, ATTITUDE_Y, 0F);
                GL11.glRotatef(pitch, 0F, 0F, 1F);
                GL11.glRotatef(roll, 1F, 0F, 0F);
                GL11.glTranslatef(0F, -ATTITUDE_Y, 0F);
            }
            GL11.glEnable(GL11.GL_NORMALIZE);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_COLOR_MATERIAL);
            GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
            GL11.glLightModeli(GL11.GL_LIGHT_MODEL_TWO_SIDE, GL11.GL_TRUE);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GL11.glDisable(GL11.GL_BLEND);
            if (lists != null) {
                LinerModelData.Pose pose = pose(v, pt);
                exterior(v, lists, pose, ownView, pt);
            }
            GL11.glLightModeli(GL11.GL_LIGHT_MODEL_TWO_SIDE, GL11.GL_FALSE);
            // the inside: the cockpit for its crew, the cabin for the passengers and anyone at the open door
            LinerCockpitSVA.render(v, pt, ownView, mc.thePlayer);
            if (ownView) {
                eyeClip(false);
            }
        } finally {
            GL11.glPopAttrib();
            GL11.glPopMatrix();
            GL11.glColor4f(1F, 1F, 1F, 1F);
        }
    }

    /** The pose of every moving group now. */
    static LinerModelData.Pose pose(EntityVehicleSVA v, float pt) {
        LinerModelData.Pose p = new LinerModelData.Pose();
        AnimMath.State an = v.anim;
        LinerAnim la = v.linerAnim;
        LinerSystems L = v.liner();
        boolean animate = ConfigSVA.animations;
        float ail = animate ? AnimMath.lerp(an.prevAileron, an.aileron, pt) : 0F;
        float elev = animate ? AnimMath.lerp(an.prevElevator, an.elevator, pt) : 0F;
        float rud = animate ? AnimMath.lerp(an.prevRudder, an.rudder, pt) : 0F;
        float steer = animate ? AnimMath.lerp(an.prevSteer, an.steer, pt) : 0F;
        float flapPos = animate ? AnimMath.lerp(an.prevFlapPos, an.flapPos, pt) : v.getFlaps();
        float flapDeg = HighLiftMath.LINER.angleAt(flapPos);
        float slat = animate ? AnimMath.lerp(an.prevSlat, an.slat, pt) : v.slatsOut() ? 1F : 0F;
        float spoil = animate ? AnimMath.lerp(an.prevSpoiler, an.spoiler, pt) : v.spoilersUp() ? 1F : 0F;
        boolean ground = v.groundSpoilersUp();
        // ailerons (+ = TE down): a right bank - the right one up, the left one down
        p.set("aileron_R", -ail * 1.1F, 0).set("aileron_L", ail * 1.1F, 0);
        p.set("elevator_R", -elev * 1.2F, 0).set("elevator_L", -elev * 1.2F, 0);
        p.set("rudder", rud * 1.2F, 0);
        for (String s : new String[] {"_R", "_L"}) {
            p.set("flap1" + s, flapDeg, flapDeg / 35F).set("flap2" + s, flapDeg, flapDeg / 35F);
            for (int i = 1; i <= 3; i++) {
                p.set("fair" + i + s, flapDeg * 0.5F, 0);
            }
            for (int i = 1; i <= 5; i++) {
                p.set("slat" + i + s, slat * 27F, 0);
            }
            // spoilers: the speed brake 2..4 (40), the ground spoilers all five (50), roll on the down-going wing 2..5
            boolean right = "_R".equals(s);
            float rollUp = Math.max(0F, (right ? ail : -ail)) / AnimMath.MAX_SURFACE * 35F;
            for (int i = 1; i <= 5; i++) {
                float a = ground ? spoil * 50F : i >= 2 && i <= 4 ? spoil * 40F : 0F;
                if (i >= 2) {
                    a = Math.max(a, rollUp);
                }
                p.set("spoiler" + i + s, Math.min(50F, a), 0);
            }
        }
        // gear: the legs, the doors, the oleos, the wheels, the nose steering
        float g = LinerAnim.lerp(la.prevGear, la.gear, pt);
        float legs = LinerAnim.legs(g), doors = LinerAnim.doors(g);
        p.set("mlg_R", LinerAnim.MLG_DEG * legs, 0).set("mlg_L", LinerAnim.MLG_DEG * legs, 0).set("nlg", LinerAnim.NLG_DEG * legs, 0);
        p.set("mlgdoor_R", LinerAnim.GEAR_DOOR_DEG * doors, 0).set("mlgdoor_L", LinerAnim.GEAR_DOOR_DEG * doors, 0);
        p.set("nlgdoor_R", LinerAnim.GEAR_DOOR_DEG * doors, 0).set("nlgdoor_L", LinerAnim.GEAR_DOOR_DEG * doors, 0);
        float oleo = LinerAnim.lerp(la.prevOleo, la.oleo, pt);
        p.set("mlgoleo_R", 0, LinerAnim.MLG_OLEO * oleo).set("mlgoleo_L", 0, LinerAnim.MLG_OLEO * oleo).set("nlgoleo", 0, LinerAnim.NLG_OLEO * oleo);
        double rolled = animate ? an.prevRoll + (an.roll - an.prevRoll) * pt : 0;
        float wm = AnimMath.wheelAngle(rolled, 0.30F), wn = AnimMath.wheelAngle(rolled, 0.20F);
        p.set("mlgwheels_R", wm, 0).set("mlgwheels_L", wm, 0).set("nlgwheels", wn, 0);
        p.set("nlgsteer", -steer * 2F, 0);
        // fans, the door and stairs, the APU inlet, the landing lights
        p.set("fan_R", LinerAnim.fan(la.prevFanR, la.fanR, pt), 0).set("fan_L", LinerAnim.fan(la.prevFanL, la.fanL, pt), 0);
        float door = LinerAnim.lerp(la.prevDoor, la.door, pt), st = LinerAnim.lerp(la.prevStairs, la.stairs, pt);
        p.set("doorL1", LinerAnim.DOOR_DEG * door, 0).set("stairs", (1F - st) * 90F, 0);
        if (L != null) {
            p.set("apu_inlet", (float) (LinerAnim.APU_FLAP_DEG * L.apuFlap), 0);
            p.set("landlight_L", L.at(LinerControls.LAND_L) != 2 ? 80F : 0F, 0).set("landlight_R", L.at(LinerControls.LAND_R) != 2 ? 80F : 0F, 0);
        }
        return p;
    }

    private static void exterior(EntityVehicleSVA v, List<GL> lists, LinerModelData.Pose pose, boolean ownView, float pt) {
        boolean tex = ConfigSVA.textures;
        int livery = tex ? LinerTexSVA.livery(v.getBoardNumber()) : 0;
        int spin = tex ? LinerTexSVA.pattern(true) : 0, casc = tex ? LinerTexSVA.pattern(false) : 0;
        float shade = v.isWrecked() ? 0.45F : 1F;
        LinerSystems L = v.liner();
        boolean power = v.powered();
        float stairs = LinerAnim.lerp(v.linerAnim.prevStairs, v.linerAnim.stairs, pt);
        float lastBx = OpenGlHelper.lastBrightnessX, lastBy = OpenGlHelper.lastBrightnessY;
        for (GL g : lists) {
            if (ownView && SHELL.contains(g.name)) {
                continue;
            }
            if ("stairs".equals(g.name) && stairs <= 0.001F) {
                continue;
            }
            GL11.glPushMatrix();
            transform(g.name, pose);
            if (g.plain != 0) {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                if (shade < 1F) {
                    GL11.glColor3f(shade, shade, shade);
                }
                GL11.glCallList(g.plain);
            }
            if (g.livery != 0) {
                if (livery > 0) {
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, livery);
                    GL11.glColor3f(shade, shade, shade);
                } else {
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    GL11.glColor3f(0.93F * shade, 0.94F * shade, 0.96F * shade);
                }
                GL11.glCallList(g.livery);
            }
            for (int k = 0; k < 2; k++) {
                int list = k == 0 ? g.spinner : g.cascade, t = k == 0 ? spin : casc;
                if (list == 0) {
                    continue;
                }
                if (t > 0) {
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
                    GL11.glColor3f(shade, shade, shade);
                } else {
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    GL11.glColor3f(0.2F, 0.21F, 0.24F);
                }
                GL11.glCallList(list);
            }
            for (int i = 0; i < g.emitList.size(); i++) {
                String m = g.emitMat.get(i);
                LinerModelData.Material ma = mats.get(m);
                boolean on = power && lightOn(m, g.name, L, v);
                int c = on ? ma.rgb : ma.off;
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                if (on) {
                    GL11.glDisable(GL11.GL_LIGHTING);
                    OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
                }
                GL11.glColor3f((c >> 16 & 255) / 255F, (c >> 8 & 255) / 255F, (c & 255) / 255F);
                GL11.glCallList(g.emitList.get(i).intValue());
                if (on) {
                    GL11.glEnable(GL11.GL_LIGHTING);
                    OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastBx, lastBy);
                }
            }
            GL11.glPopMatrix();
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    /** A light material on (by its switch on the overhead; strobes and beacons flash). */
    static boolean lightOn(String mat, String group, LinerSystems L, EntityVehicleSVA v) {
        if (L == null) {
            return false;
        }
        int t = v.ticksExisted;
        if ("nav_r".equals(mat) || "nav_g".equals(mat) || "nav_w".equals(mat) || "logo".equals(mat)) {
            return L.at(LinerControls.NAV_LOGO) != 1;
        }
        if ("strobe".equals(mat)) {
            boolean sw = L.at(LinerControls.STROBE) == 0 || L.at(LinerControls.STROBE) == 1 && v.anim.height > 1;
            return sw && t % AnimMath.STROBE_PERIOD < AnimMath.STROBE_ON;
        }
        if ("beacon".equals(mat)) {
            return L.at(LinerControls.BEACON) == 0 && (t + ("beacon_bot".equals(group) ? 10 : 0)) % AnimMath.BEACON_PERIOD < 5;
        }
        if ("land".equals(mat)) {
            return "landlight_L".equals(group) ? L.at(LinerControls.LAND_L) == 0 : L.at(LinerControls.LAND_R) == 0;
        }
        if ("taxi".equals(mat)) {
            return L.at(LinerControls.NOSE_LT) != 2;
        }
        if ("rto".equals(mat)) {
            return L.at(LinerControls.RWY_TURN) == 0;
        }
        if ("wingl".equals(mat)) {
            return L.at(LinerControls.WING_LT) == 0;
        }
        return false;
    }

    /** A group's transform (its parents' first): the track, then the turn about the hinge. */
    static void transform(String name, LinerModelData.Pose pose) {
        LinerModelData.Joint j = rig.get(name);
        if (j == null) {
            return;
        }
        if (j.parent.length() > 0) {
            transform(j.parent, pose);
        }
        float a = pose.angle(name), tr = pose.travel(name);
        if (j.track != null && tr != 0F) {
            GL11.glTranslatef(j.track[0] * tr, j.track[1] * tr, j.track[2] * tr);
        }
        if (j.pivot != null && j.axis != null && a != 0F) {
            GL11.glTranslatef(j.pivot[0], j.pivot[1], j.pivot[2]);
            GL11.glRotatef(a, j.axis[0], j.axis[1], j.axis[2]);
            GL11.glTranslatef(-j.pivot[0], -j.pivot[1], -j.pivot[2]);
        }
    }

    /** The item's slot / hand: LOD2 without the lights' glow, at rest (RenderItemVehicleSVA hands the liner over). */
    static boolean renderItem() {
        List<GL> lists = best(2);
        if (lists == null) {
            return false;
        }
        LinerModelData.Pose pose = new LinerModelData.Pose();
        GL11.glLightModeli(GL11.GL_LIGHT_MODEL_TWO_SIDE, GL11.GL_TRUE);
        int livery = ConfigSVA.textures ? LinerTexSVA.livery(1) : 0;
        for (GL g : lists) {
            if ("stairs".equals(g.name)) {
                continue;
            }
            GL11.glPushMatrix();
            transform(g.name, pose);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            if (g.plain != 0) {
                GL11.glCallList(g.plain);
            }
            if (g.livery != 0) {
                if (livery > 0) {
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, livery);
                    GL11.glColor3f(1F, 1F, 1F);
                } else {
                    GL11.glColor3f(0.93F, 0.94F, 0.96F);
                }
                GL11.glCallList(g.livery);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
            }
            for (int i = 0; i < g.emitList.size(); i++) {
                int c = mats.get(g.emitMat.get(i)).off;
                GL11.glColor3f((c >> 16 & 255) / 255F, (c >> 8 & 255) / 255F, (c & 255) / 255F);
                GL11.glCallList(g.emitList.get(i).intValue());
            }
            GL11.glPopMatrix();
        }
        GL11.glLightModeli(GL11.GL_LIGHT_MODEL_TWO_SIDE, GL11.GL_FALSE);
        return true;
    }

    /** The item's bounds {minX, minY, minZ, maxX, maxY, maxZ} (the concept's bounding box). */
    static final float[] ITEM_BOUNDS = {-9.24F, 0F, -8.78F, 9.22F, 5.65F, 8.78F};

    private static final int EYE_CLIP_PLANE = GL11.GL_CLIP_PLANE5;
    private static final java.nio.DoubleBuffer EYE_EQ = org.lwjgl.BufferUtils.createDoubleBuffer(4);

    /** RenderVehicleSVA.eyeClip's twin: nothing behind the eye plane in the own view (the seat's back, the headrest). */
    static void eyeClip(boolean on) {
        if (!on) {
            GL11.glDisable(EYE_CLIP_PLANE);
            return;
        }
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        EYE_EQ.clear();
        EYE_EQ.put(0).put(0).put(-1).put(-com.sva.vehicle.CockpitView.EYE_CLIP).flip();
        GL11.glClipPlane(EYE_CLIP_PLANE, EYE_EQ);
        GL11.glPopMatrix();
        GL11.glEnable(EYE_CLIP_PLANE);
    }

    /** Whether the type is drawn here. */
    static boolean handles(VehicleType t) {
        return t == VehicleType.LINER;
    }

    static Map<String, LinerModelData.Joint> rig() {
        return rig;
    }
}
