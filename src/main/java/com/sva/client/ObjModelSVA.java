package com.sva.client;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import com.sva.ReferenceSVA;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;

/**
 * A vehicle model: models/NAME.obj (one "g material" group per material, smooth vertex normals) coloured from
 * NAME.groups.txt ("material r g b emissive"). A small parser of our own instead of Forge's WavefrontObject,
 * which shades with face normals only. Each group is compiled into a GL display list ONCE (the first time it
 * is drawn); the vertex data is dropped afterwards.
 * Moving parts (rotors, propellers) are cut out of the material groups when the model loads (see Part / PARTS): their
 * triangles go to extra groups "#part/material" that render() leaves out and renderPart() draws turned.
 */
public final class ObjModelSVA {

    private static final Map<String, ObjModelSVA> CACHE = new HashMap<String, ObjModelSVA>();

    /**
     * A part that moves: the triangles of `groups` whose centre lies in the box [min, max] (model units), turning
     * about `axis` (0 x, 1 y, 2 z) through `pivot` at `speed` x the vehicle's rotor angle; `disc` > 0 = the radius of
     * the translucent motion-blur disc drawn while it spins fast.
     */
    public static final class Part {
        /**
         * ROTOR: turned by the rotor angle x speed; TRAILER: drawn only while a trailer is hitched; BORE: the drill's cone;
         * NACELLE (stage D, the tiltrotor): first tilted forward about z through `tiltPivot` by the nacelle angle
         * (0 rotors up .. 90 forward), then spun about its own y axis through `pivot` like a ROTOR.
         */
        public static final int ROTOR = 0, TRAILER = 1, BORE = 2, NACELLE = 3;
        /** NACELLE: the point the rotor swings forward about (model units). */
        public float[] tiltPivot;
        public final String name;
        final java.util.Set<String> groups = new java.util.HashSet<String>();
        /** No `groups` given: every material but these. */
        final java.util.Set<String> exclude = new java.util.HashSet<String>();
        final float[] min, max;
        public final float[] pivot;
        public final int axis;
        public final float speed, disc;
        public final int kind;

        Part(String name, String[] groups, float[] min, float[] max, float[] pivot, int axis, float speed, float disc) {
            this(ROTOR, name, groups, null, min, max, pivot, axis, speed, disc);
        }

        Part(int kind, String name, String[] groups, String[] exclude, float[] min, float[] max, float[] pivot, int axis, float speed, float disc) {
            this.kind = kind;
            this.name = name;
            if (groups != null) {
                java.util.Collections.addAll(this.groups, groups);
            }
            if (exclude != null) {
                java.util.Collections.addAll(this.exclude, exclude);
            }
            this.min = min;
            this.max = max;
            this.pivot = pivot;
            this.axis = axis;
            this.speed = speed;
            this.disc = disc;
        }

        boolean holds(String group, float x, float y, float z) {
            boolean g = groups.isEmpty() ? !exclude.contains(group) : groups.contains(group);
            return g && x >= min[0] && x <= max[0] && y >= min[1] && y <= max[1] && z >= min[2] && z <= max[2];
        }
    }

    /** Model name -> its moving parts. Numbers measured from the exported OBJ (export_obj.py: x forward, y up, centred). */
    private static final Map<String, List<Part>> PARTS = new HashMap<String, List<Part>>();

    static {
        // heli.obj: the main rotor - 4 blades (dark, hazard tips, r 2.25) and the hub (chrome) over the mast at x 0.364,
        // z 0, y 1.86..2.04; nothing else of those materials is up there
        part("heli", new Part("rotor", new String[] {"dark", "hazard", "chrome"}, new float[] {-1.95F, 1.85F, -2.4F},
                new float[] {2.7F, 2.1F, 2.4F}, new float[] {0.364F, 1.92F, 0F}, 1, 1F, 2.3F));
        // the tail rotor: two dark blades at x -2.32..-2.26 turning about x through the concept's hub (-42.5, 25, 1.6)/16
        part("heli", new Part("tail", new String[] {"dark"}, new float[] {-2.4F, 1.0F, -0.2F},
                new float[] {-2.2F, 2.1F, 0.8F}, new float[] {-2.292F, 1.544F, 0.1F}, 0, 3F, 0F));
        // plane.obj: the 3-blade propeller (dark, hazard tips) at x 2.27..2.30, about x through the spinner (2.284, 0.631, 0.019)
        part("plane", new Part("prop", new String[] {"dark", "hazard"}, new float[] {2.25F, 0F, -0.9F},
                new float[] {2.4F, 1.4F, 0.9F}, new float[] {2.284F, 0.631F, 0.019F}, 0, 1F, 0.72F));
        // truck.obj: the trailer - everything behind the hitch (triangle centres x < -1.25; the crawler's tracks reach
        // -1.2 and stay), drawn only while one is hitched
        part("truck", new Part(Part.TRAILER, "trailer", null, new String[] {"tread"}, new float[] {-9F, -1F, -3F},
                new float[] {-1.25F, 4F, 3F}, new float[] {0F, 0F, 0F}, 1, 0F, 0F));
        // drill.obj: the bore cone (steel, chrome, gold teeth; x >= 1.44 - the collar ring at 1.38..1.44 stays) about x
        // through its tip (3.0, 1.031, 0.019)
        part("drill", new Part(Part.BORE, "bore", new String[] {"steel", "chrome", "gold"}, null, new float[] {1.44F, 0F, -1.2F},
                new float[] {3.2F, 2.2F, 1.2F}, new float[] {2.2F, 1.031F, 0.019F}, 0, 1F, 0F));
        nacelle(1);
        nacelle(-1);
    }

    /**
     * tilt.obj's proprotors (stage D): per wing tip the mast (steel, x 0.29..0.39, y 1.28..1.59), the hub (chrome, centre
     * (0.344, 1.64, +-2.25)) and three blades (dark, hazard tips; y 1.62..1.65, r 1.35) - drawn for vertical mode. They
     * swing forward about z through (0.6, 0.838): at 90 deg the mast foot sits on the nacelle's nose (x 1.04, y 1.09) and
     * the disc stands at x 1.40, clear of the wing (x <= 0.84) and the fuselage (|z| <= 0.41). The nacelle bodies
     * (paint2) stay as drawn. The left rotor turns the other way.
     */
    private static void nacelle(int side) {
        Part p = new Part(Part.NACELLE, side > 0 ? "nacelleR" : "nacelleL", new String[] {"steel", "chrome", "dark", "hazard"}, null,
                new float[] {-1.1F, 1.27F, side > 0 ? 0.85F : -3.7F}, new float[] {1.5F, 1.72F, side > 0 ? 3.7F : -0.85F},
                new float[] {0.344F, 1.64F, side * 2.25F}, 1, side, 1.35F);
        p.tiltPivot = new float[] {0.6F, 0.838F, side * 2.25F};
        part("tilt", p);
    }

    private static void part(String model, Part p) {
        List<Part> l = PARTS.get(model);
        if (l == null) {
            l = new ArrayList<Part>();
            PARTS.put(model, l);
        }
        l.add(p);
    }

    private final String name;
    private final List<String> groups = new ArrayList<String>();
    private final List<float[]> colors = new ArrayList<float[]>();
    private final List<Boolean> emissive = new ArrayList<Boolean>();
    /** group -> triangle vertices {x, y, z, nx, ny, nz}, until compiled. */
    private Map<String, List<float[]>> pending;
    private int lists = -1;
    private boolean failed;

    private ObjModelSVA(String name) {
        this.name = name;
    }

    /** This model's moving parts (empty for most). */
    public List<Part> parts() {
        List<Part> l = PARTS.get(name);
        return l == null ? java.util.Collections.<Part>emptyList() : l;
    }

    public static ObjModelSVA get(String name) {
        ObjModelSVA m = CACHE.get(name);
        if (m == null) {
            m = new ObjModelSVA(name);
            CACHE.put(name, m);
            m.load();
        }
        return m.failed ? null : m;
    }

    private InputStream open(String file) throws java.io.IOException {
        return Minecraft.getMinecraft().getResourceManager()
                .getResource(new ResourceLocation(ReferenceSVA.ASSETS, "models/" + file)).getInputStream();
    }

    private void load() {
        Map<String, float[]> palette = new HashMap<String, float[]>();
        Map<String, Boolean> glow = new HashMap<String, Boolean>();
        pending = new LinkedHashMap<String, List<float[]>>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(open(name + ".groups.txt"), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.length() == 0 || line.startsWith("#")) {
                        continue;
                    }
                    String[] p = line.split("\\s+");
                    if (p.length >= 4) {
                        palette.put(p[0], new float[] {Integer.parseInt(p[1]) / 255F, Integer.parseInt(p[2]) / 255F, Integer.parseInt(p[3]) / 255F});
                        glow.put(p[0], Boolean.valueOf(p.length > 4 && "1".equals(p[4])));
                    }
                }
            } finally {
                r.close();
            }
            List<float[]> v = new ArrayList<float[]>(), vn = new ArrayList<float[]>();
            List<float[]> cur = null;
            String curName = "default";
            List<Part> parts = parts();
            r = new BufferedReader(new InputStreamReader(open(name + ".obj"), "UTF-8"), 1 << 16);
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("v ")) {
                        v.add(floats(line));
                    } else if (line.startsWith("vn ")) {
                        vn.add(floats(line));
                    } else if (line.startsWith("g ") || line.startsWith("o ") || line.startsWith("usemtl ")) {
                        String g = line.substring(line.indexOf(' ') + 1).trim();
                        curName = g;
                        cur = pending.get(g);
                        if (cur == null) {
                            cur = new ArrayList<float[]>();
                            pending.put(g, cur);
                        }
                    } else if (line.startsWith("f ")) {
                        if (cur == null) {
                            cur = new ArrayList<float[]>();
                            pending.put("default", cur);
                        }
                        String[] p = line.substring(2).trim().split("\\s+");
                        float[][] poly = new float[p.length][];
                        for (int i = 0; i < p.length; i++) {
                            String[] ix = p[i].split("/");
                            float[] pos = v.get(Integer.parseInt(ix[0]) - 1);
                            float[] n = ix.length >= 3 && ix[2].length() > 0 ? vn.get(Integer.parseInt(ix[2]) - 1) : null;
                            poly[i] = new float[] {pos[0], pos[1], pos[2], n == null ? 0 : n[0], n == null ? 1 : n[1], n == null ? 0 : n[2]};
                        }
                        for (int i = 1; i + 1 < poly.length; i++) {
                            List<float[]> to = cur;
                            for (Part pt : parts) {
                                if (pt.holds(curName, (poly[0][0] + poly[i][0] + poly[i + 1][0]) / 3F,
                                        (poly[0][1] + poly[i][1] + poly[i + 1][1]) / 3F, (poly[0][2] + poly[i][2] + poly[i + 1][2]) / 3F)) {
                                    String key = "#" + pt.name + "/" + curName;
                                    to = pending.get(key);
                                    if (to == null) {
                                        to = new ArrayList<float[]>();
                                        pending.put(key, to);
                                    }
                                    break;
                                }
                            }
                            to.add(poly[0]);
                            to.add(poly[i]);
                            to.add(poly[i + 1]);
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            cpw.mods.fml.common.FMLLog.warning("[SVA] model %s failed to load: %s", name, e);
            failed = true;
            return;
        }
        for (String g : pending.keySet()) {
            groups.add(g);
            String mat = g.startsWith("#") ? g.substring(g.indexOf('/') + 1) : g;     // a part keeps its material's colour
            float[] c = palette.get(mat);
            colors.add(c != null ? c : new float[] {0.6F, 0.6F, 0.6F});
            Boolean e = glow.get(mat);
            emissive.add(e != null ? e : Boolean.FALSE);
        }
    }

    private static float[] floats(String line) {
        String[] p = line.trim().split("\\s+");
        return new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])};
    }

    private void compile() {
        lists = GL11.glGenLists(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            GL11.glNewList(lists + i, GL11.GL_COMPILE);
            GL11.glBegin(GL11.GL_TRIANGLES);
            for (float[] p : pending.get(groups.get(i))) {
                GL11.glNormal3f(p[3], p[4], p[5]);
                GL11.glVertex3f(p[0], p[1], p[2]);
            }
            GL11.glEnd();
            GL11.glEndList();
        }
        pending = null;
    }

    /** Glass groups: drawn after the opaque ones, see-through. */
    public static boolean isGlass(String group) {
        return "glass".equals(group) || "canopy".equals(group);
    }

    /** Glass alpha and how far its colour is lifted toward white (the OBJ colour is a dark tint). */
    public static final float GLASS_ALPHA = 0.28F, GLASS_LIGHTEN = 0.55F;

    public void render(float shade) {
        render(shade, true);
    }

    /**
     * Draws the opaque groups, then (withGlass) the glass / canopy groups translucent: blended, tinted lighter,
     * no depth writes, both faces (texture 2D must already be off). `shade` darkens everything (a wreck: no glow).
     * withGlass false: the glass is left out (the local rider's first-person view).
     */
    public void render(float shade, boolean withGlass) {
        if (lists < 0) {
            compile();
        }
        for (int i = 0; i < groups.size(); i++) {
            if (isGlass(groups.get(i)) || groups.get(i).startsWith("#")) {
                continue;
            }
            drawGroup(i, shade);
        }
        if (withGlass) {
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            for (int i = 0; i < groups.size(); i++) {
                if (!isGlass(groups.get(i))) {
                    continue;
                }
                float[] c = colors.get(i);
                GL11.glColor4f(lighten(c[0]) * shade, lighten(c[1]) * shade, lighten(c[2]) * shade, GLASS_ALPHA);
                GL11.glCallList(lists + i);
            }
            GL11.glDepthMask(true);
            GL11.glPopAttrib();
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private static float lighten(float c) {
        return c + (1F - c) * GLASS_LIGHTEN;
    }

    /** One opaque group: lit and shaded, or (emissive, not a wreck) full bright. */
    private void drawGroup(int i, float shade) {
        float[] c = colors.get(i);
        if (emissive.get(i).booleanValue() && shade >= 1F) {
            float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
            GL11.glDisable(GL11.GL_LIGHTING);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            GL11.glColor4f(c[0], c[1], c[2], 1F);
            GL11.glCallList(lists + i);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
            GL11.glEnable(GL11.GL_LIGHTING);
        } else {
            GL11.glColor4f(c[0] * shade, c[1] * shade, c[2] * shade, 1F);
            GL11.glCallList(lists + i);
        }
    }

    /** Draws a moving part turned by `angle` degrees about its axis through its pivot (same state as render()). */
    public void renderPart(Part p, float angle, float shade) {
        if (lists < 0) {
            compile();
        }
        String prefix = "#" + p.name + "/";
        GL11.glPushMatrix();
        GL11.glTranslatef(p.pivot[0], p.pivot[1], p.pivot[2]);
        GL11.glRotatef(angle, p.axis == 0 ? 1F : 0F, p.axis == 1 ? 1F : 0F, p.axis == 2 ? 1F : 0F);
        GL11.glTranslatef(-p.pivot[0], -p.pivot[1], -p.pivot[2]);
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).startsWith(prefix)) {
                drawGroup(i, shade);
            }
        }
        GL11.glPopMatrix();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /**
     * A NACELLE part: swung forward by `tilt` degrees (about z through p.tiltPivot), spun by `spin` about its mast, with
     * its blur disc (alpha `blur`) in the same frame.
     */
    public void renderNacelle(Part p, float tilt, float spin, float shade, float blur) {
        GL11.glPushMatrix();
        GL11.glTranslatef(p.tiltPivot[0], p.tiltPivot[1], p.tiltPivot[2]);
        GL11.glRotatef(-tilt, 0F, 0F, 1F);                            // +y (rotor up) -> +x (forward) at 90
        GL11.glTranslatef(-p.tiltPivot[0], -p.tiltPivot[1], -p.tiltPivot[2]);
        renderPart(p, spin, shade);
        renderDisc(p, blur);
        GL11.glPopMatrix();
    }

    /** The jet's afterburner: a glowing cone (additive, full bright) behind the nozzle at (x, y, 0), radius r, `len` long. */
    public static void renderFlame(float x, float y, float r, float len, float flicker) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT);
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_LIGHTING);
        for (int layer = 0; layer < 2; layer++) {
            float rr = layer == 0 ? r * 1.05F : r * 0.6F, l = (layer == 0 ? len : len * 0.6F) * flicker;
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            if (layer == 0) {
                GL11.glColor4f(1F, 0.55F, 0.15F, 0.0F);
            } else {
                GL11.glColor4f(0.6F, 0.8F, 1F, 0.0F);
            }
            GL11.glVertex3f(x - l, y, 0F);
            for (int k = 0; k <= 16; k++) {
                double a = k * Math.PI * 2 / 16;
                if (layer == 0) {
                    GL11.glColor4f(1F, 0.6F, 0.2F, 0.75F);
                } else {
                    GL11.glColor4f(0.75F, 0.85F, 1F, 0.8F);
                }
                GL11.glVertex3f(x, y + (float) Math.cos(a) * rr, (float) Math.sin(a) * rr);
            }
            GL11.glEnd();
        }
        GL11.glDepthMask(true);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
        GL11.glPopAttrib();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** The translucent motion-blur disc of a fast-spinning part (radius p.disc, across its axis through the pivot). */
    public static void renderDisc(Part p, float alpha) {
        if (p.disc <= 0 || alpha <= 0) {
            return;
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColor4f(0.16F, 0.17F, 0.19F, alpha);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex3f(p.pivot[0], p.pivot[1], p.pivot[2]);
        for (int k = 0; k <= 32; k++) {
            double a = k * Math.PI * 2 / 32;
            float u = (float) (Math.cos(a) * p.disc), w = (float) (Math.sin(a) * p.disc);
            if (p.axis == 1) {
                GL11.glVertex3f(p.pivot[0] + u, p.pivot[1], p.pivot[2] + w);
            } else if (p.axis == 0) {
                GL11.glVertex3f(p.pivot[0], p.pivot[1] + u, p.pivot[2] + w);
            } else {
                GL11.glVertex3f(p.pivot[0] + u, p.pivot[1] + w, p.pivot[2]);
            }
        }
        GL11.glEnd();
        GL11.glDepthMask(true);
        GL11.glPopAttrib();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** Draws every group in one colour (the workbench hologram); the caller sets blending / lighting. */
    public void renderTinted(float r, float g, float b, float a) {
        if (lists < 0) {
            compile();
        }
        GL11.glColor4f(r, g, b, a);
        for (int i = 0; i < groups.size(); i++) {
            GL11.glCallList(lists + i);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }
}
