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
 */
public final class ObjModelSVA {

    private static final Map<String, ObjModelSVA> CACHE = new HashMap<String, ObjModelSVA>();

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
                            cur.add(poly[0]);
                            cur.add(poly[i]);
                            cur.add(poly[i + 1]);
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
            float[] c = palette.get(g);
            colors.add(c != null ? c : new float[] {0.6F, 0.6F, 0.6F});
            Boolean e = glow.get(g);
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

    /** Draws every group (texture 2D must already be off). `shade` darkens everything (a wreck: no glow either). */
    public void render(float shade) {
        if (lists < 0) {
            compile();
        }
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        for (int i = 0; i < groups.size(); i++) {
            float[] c = colors.get(i);
            if (emissive.get(i).booleanValue() && shade >= 1F) {
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
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }
}
