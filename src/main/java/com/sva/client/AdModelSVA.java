package com.sva.client;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GLContext;

import com.sva.ConfigSVA;
import com.sva.ReferenceSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;

/**
 * Multiblock wave: an air-defence model (models/ad_NAME.obj + ad_NAME.groups.txt, written by ad_models.py): groups
 * "PART/MATERIAL" (+ "/d" for the small details the far LOD leaves out), positions in blocks (x forward, y up, z right,
 * the footprint's centre at the origin), box-projected UVs (1 texture tile a block, the screens fitted to their face).
 * Each group is compiled into one display list the first time it is drawn; the vertex data is dropped afterwards.
 * Textures (textures/models/ad_*.png, 512 px a block) are uploaded here with their own mipmap chain (box filtered, the
 * GL texture ours - not the block atlas, so 512 px tiles stay sharp up close and calm at a distance; 4x anisotropic
 * where the card has it). Config airdefense.modelTextures off: the groups.txt colours only.
 *
 * Drawing: begin() sets the GL state once, part() draws a part's lists (the renderer turns them between calls), end()
 * restores. No allocation per frame.
 */
@SideOnly(Side.CLIENT)
public final class AdModelSVA {

    private static final Map<String, AdModelSVA> CACHE = new HashMap<String, AdModelSVA>();
    private static final Map<String, Integer> TEXTURES = new HashMap<String, Integer>();

    /** A material of groups.txt: colour, emissive, texture (null none). */
    private static final class Mat {
        float r, g, b;
        boolean emissive;
        String tex;
    }

    /** One compiled group. */
    private static final class Group {
        final String part, mat;
        final boolean detail;
        Mat m;
        List<float[]> verts = new ArrayList<float[]>();     // x y z u v nx ny nz, until compiled
        int list;

        Group(String part, String mat, boolean detail) {
            this.part = part;
            this.mat = mat;
            this.detail = detail;
        }
    }

    public final String name;
    private final Map<String, Mat> mats = new HashMap<String, Mat>();
    private final Map<String, float[]> pivots = new HashMap<String, float[]>();
    private final Map<String, float[]> points = new HashMap<String, float[]>();
    /** part -> its groups (lit first, emissive last). */
    private final Map<String, Group[]> parts = new LinkedHashMap<String, Group[]>();
    public final float[] bounds = {-1F, 0F, -1F, 1F, 1F, 1F};
    private boolean compiled;

    /** Per draw (set by the renderer): the accent stripe's colour and whether it is the MV hazard band instead. */
    private float accR = 1F, accG = 0.55F, accB = 0.2F;
    private boolean hazard, detail = true;
    private float lastBx, lastBy;

    private AdModelSVA(String name) {
        this.name = name;
    }

    /** The model (cached; null if its files are missing or broken - the renderer then draws the plain block). */
    public static AdModelSVA get(String name) {
        if (CACHE.containsKey(name)) {
            return CACHE.get(name);
        }
        AdModelSVA m = new AdModelSVA(name);
        try {
            m.load();
        } catch (Exception e) {
            System.out.println("[SVA] air-defence model " + name + " not loaded: " + e);
            m = null;
        }
        CACHE.put(name, m);
        return m;
    }

    private static BufferedReader open(String path) throws Exception {
        InputStream in = Minecraft.getMinecraft().getResourceManager().getResource(new ResourceLocation(ReferenceSVA.ASSETS, path)).getInputStream();
        return new BufferedReader(new InputStreamReader(in, "UTF-8"));
    }

    private void load() throws Exception {
        BufferedReader r = open("models/" + name + ".groups.txt");
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String[] t = line.trim().split("\\s+");
                if (t.length >= 7 && t[0].equals("mat")) {
                    Mat m = new Mat();
                    m.r = Integer.parseInt(t[2]) / 255F;
                    m.g = Integer.parseInt(t[3]) / 255F;
                    m.b = Integer.parseInt(t[4]) / 255F;
                    m.emissive = t[5].equals("1");
                    m.tex = t[6].equals("-") ? null : t[6];
                    mats.put(t[1], m);
                } else if (t.length >= 5 && (t[0].equals("pivot") || t[0].equals("point"))) {
                    float[] p = {Float.parseFloat(t[2]), Float.parseFloat(t[3]), Float.parseFloat(t[4])};
                    (t[0].equals("pivot") ? pivots : points).put(t[1], p);
                } else if (t.length >= 7 && t[0].equals("bounds")) {
                    for (int i = 0; i < 6; i++) {
                        bounds[i] = Float.parseFloat(t[i + 1]);
                    }
                }
            }
        } finally {
            r.close();
        }
        List<float[]> v = new ArrayList<float[]>(), vt = new ArrayList<float[]>(), vn = new ArrayList<float[]>();
        Map<String, Group> groups = new LinkedHashMap<String, Group>();
        Group cur = null;
        r = open("models/" + name + ".obj");
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("v ")) {
                    String[] t = line.split("\\s+");
                    v.add(new float[] {Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3])});
                } else if (line.startsWith("vt ")) {
                    String[] t = line.split("\\s+");
                    vt.add(new float[] {Float.parseFloat(t[1]), Float.parseFloat(t[2])});
                } else if (line.startsWith("vn ")) {
                    String[] t = line.split("\\s+");
                    vn.add(new float[] {Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3])});
                } else if (line.startsWith("g ")) {
                    String key = line.substring(2).trim();
                    cur = groups.get(key);
                    if (cur == null) {
                        String[] t = key.split("/");
                        cur = new Group(t[0], t.length > 1 ? t[1] : "olive", t.length > 2 && t[2].equals("d"));
                        groups.put(key, cur);
                    }
                } else if (line.startsWith("f ") && cur != null) {
                    String[] t = line.split("\\s+");
                    for (int k = 1; k <= 3 && k < t.length; k++) {
                        String[] i = t[k].split("/");
                        float[] p = v.get(Integer.parseInt(i[0]) - 1);
                        float[] uv = i.length > 1 && i[1].length() > 0 ? vt.get(Integer.parseInt(i[1]) - 1) : new float[2];
                        float[] n = i.length > 2 ? vn.get(Integer.parseInt(i[2]) - 1) : new float[] {0F, 1F, 0F};
                        cur.verts.add(new float[] {p[0], p[1], p[2], uv[0], uv[1], n[0], n[1], n[2]});
                    }
                }
            }
        } finally {
            r.close();
        }
        Map<String, List<Group>> byPart = new LinkedHashMap<String, List<Group>>();
        for (Group g : groups.values()) {
            g.m = mats.get(g.mat);
            if (g.m == null) {
                g.m = new Mat();
                g.m.r = g.m.g = g.m.b = 0.5F;
            }
            List<Group> l = byPart.get(g.part);
            if (l == null) {
                l = new ArrayList<Group>();
                byPart.put(g.part, l);
            }
            l.add(g);
        }
        for (Map.Entry<String, List<Group>> e : byPart.entrySet()) {
            List<Group> l = e.getValue();
            List<Group> sorted = new ArrayList<Group>();
            for (Group g : l) {
                if (!g.m.emissive) {
                    sorted.add(g);
                }
            }
            for (Group g : l) {
                if (g.m.emissive) {
                    sorted.add(g);
                }
            }
            parts.put(e.getKey(), sorted.toArray(new Group[sorted.size()]));
        }
    }

    public boolean has(String part) {
        return parts.containsKey(part);
    }

    /** A part's pivot (model blocks), {0, 0, 0} if the model has none. */
    public float[] pivot(String part) {
        float[] p = pivots.get(part);
        return p == null ? new float[3] : p;
    }

    /** A named point (the muzzles, the tubes, the beacon), null if none. */
    public float[] point(String name) {
        return points.get(name);
    }

    // ------------------------------------------------------------------ compile

    private void compile() {
        compiled = true;
        boolean tex = ConfigSVA.adModelTextures;
        for (Group[] gs : parts.values()) {
            for (Group g : gs) {
                g.list = GL11.glGenLists(1);
                GL11.glNewList(g.list, GL11.GL_COMPILE);
                GL11.glBegin(GL11.GL_TRIANGLES);
                for (float[] q : g.verts) {
                    GL11.glNormal3f(q[5], q[6], q[7]);
                    if (tex) {
                        GL11.glTexCoord2f(q[3], q[4]);
                    }
                    GL11.glVertex3f(q[0], q[1], q[2]);
                }
                GL11.glEnd();
                GL11.glEndList();
                g.verts = null;
            }
        }
    }

    /** Textures are uploaded once (shared by every model); their ids stay for the session. */
    private static int texture(String name) {
        Integer id = TEXTURES.get(name);
        if (id != null) {
            return id.intValue();
        }
        int t = 0;
        try {
            InputStream in = Minecraft.getMinecraft().getResourceManager()
                    .getResource(new ResourceLocation(ReferenceSVA.ASSETS, "textures/models/" + name + ".png")).getInputStream();
            BufferedImage img;
            try {
                img = ImageIO.read(in);
            } finally {
                in.close();
            }
            t = upload(img);
        } catch (Exception e) {
            System.out.println("[SVA] air-defence texture " + name + " not loaded: " + e);
        }
        TEXTURES.put(name, Integer.valueOf(t));
        return t;
    }

    /**
     * Our own GL texture with a full mipmap chain (each level the 2x2 average of the one above, AdMipMath), repeat wrap,
     * trilinear filtering, 4x anisotropic if supported. The previously bound texture is bound back.
     */
    private static int upload(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        int level = 0;
        while (true) {
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
            for (int i = 0; i < w * h; i++) {
                int c = argb[i];
                buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
            }
            buf.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            if (w == 1 && h == 1) {
                break;
            }
            argb = com.sva.airdefense.AdMipMath.half(argb, w, h);
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            level++;
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, level);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        try {
            ContextCapabilities caps = GLContext.getCapabilities();
            if (caps.GL_EXT_texture_filter_anisotropic) {
                float max = GL11.glGetFloat(0x84FF);                    // GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT
                GL11.glTexParameterf(GL11.GL_TEXTURE_2D, 0x84FE, Math.min(4F, max));   // GL_TEXTURE_MAX_ANISOTROPY_EXT
            }
        } catch (Throwable t) {
            // no anisotropic filtering: trilinear only
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        return id;
    }

    // ------------------------------------------------------------------ draw

    /**
     * Sets the state for this model's parts: lit, smooth, both sides (the dishes are open surfaces), rescaled normals.
     * `accentRgb` the tier stripe (0xRRGGBB), `hazardBand` the MV livery's black-yellow band instead, `withDetail` false
     * beyond the LOD distance. Pushes the enable / lighting / current / texture attribute bits (end() pops them).
     */
    public void begin(int accentRgb, boolean hazardBand, boolean withDetail) {
        if (!compiled) {
            compile();
        }
        accR = (accentRgb >> 16 & 255) / 255F;
        accG = (accentRgb >> 8 & 255) / 255F;
        accB = (accentRgb & 255) / 255F;
        hazard = hazardBand;
        detail = withDetail;
        lastBx = OpenGlHelper.lastBrightnessX;
        lastBy = OpenGlHelper.lastBrightnessY;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
    }

    public void end() {
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastBx, lastBy);
        GL11.glPopAttrib();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** Draws a part as modelled (lamps: their groups.txt colour). */
    public void part(String part) {
        part(part, -1, false);
    }

    /**
     * Draws a part; `rgb` >= 0 recolours it (the status lamps: lit = `glow` true, full bright; dark = drawn lit by the
     * world in a dim shade of the colour).
     */
    public void part(String part, int rgb, boolean glow) {
        Group[] gs = parts.get(part);
        if (gs == null) {
            return;
        }
        boolean texOn = ConfigSVA.adModelTextures;
        for (Group g : gs) {
            if (g.detail && !detail) {
                continue;
            }
            Mat m = g.m;
            boolean emissive = rgb >= 0 ? glow : m.emissive;
            float r = m.r, gg = m.g, b = m.b;
            if (rgb >= 0) {
                float k = glow ? 1F : 0.28F;
                r = (rgb >> 16 & 255) / 255F * k;
                gg = (rgb >> 8 & 255) / 255F * k;
                b = (rgb & 255) / 255F * k;
            }
            String tex = m.tex;
            if (g.mat.equals("accent")) {
                if (hazard) {
                    tex = "ad_hazard";
                    r = gg = b = 1F;
                } else {
                    r = accR;
                    gg = accG;
                    b = accB;
                }
            } else if (texOn && tex != null && rgb < 0 && !m.emissive) {
                r = gg = b = 1F;                                    // the texture has the colour
            }
            if (texOn && tex != null && rgb < 0) {
                int id = texture(tex);
                if (id != 0) {
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
                    if (m.emissive) {
                        r = gg = b = 1F;                            // a lit screen: its own picture at full brightness
                    }
                } else {
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                }
            } else {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
            }
            if (emissive) {
                GL11.glDisable(GL11.GL_LIGHTING);
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            }
            GL11.glColor4f(r, gg, b, 1F);
            GL11.glCallList(g.list);
            if (emissive) {
                GL11.glEnable(GL11.GL_LIGHTING);
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastBx, lastBy);
            }
        }
    }

    /** Turns the GL matrix about a part's pivot: about y by `yaw`, then about z by `pitch` (degrees). */
    public void turn(String part, float yaw, float pitch) {
        float[] p = pivot(part);
        GL11.glTranslatef(p[0], p[1], p[2]);
        if (yaw != 0F) {
            GL11.glRotatef(yaw, 0F, 1F, 0F);
        }
        if (pitch != 0F) {
            GL11.glRotatef(pitch, 0F, 0F, 1F);
        }
        GL11.glTranslatef(-p[0], -p[1], -p[2]);
    }

    /** Drops every display list and texture (resource reload / leaving the world is not needed - they stay valid). */
    public static void clear() {
        for (AdModelSVA m : CACHE.values()) {
            if (m != null && m.compiled) {
                for (Group[] gs : m.parts.values()) {
                    for (Group g : gs) {
                        if (g.list != 0) {
                            GL11.glDeleteLists(g.list, 1);
                        }
                    }
                }
            }
        }
        CACHE.clear();
        for (Integer id : TEXTURES.values()) {
            if (id.intValue() != 0) {
                GL11.glDeleteTextures(id.intValue());
            }
        }
        TEXTURES.clear();
    }
}
