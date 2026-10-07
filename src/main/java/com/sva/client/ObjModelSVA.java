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

import com.sva.ConfigSVA;
import com.sva.ReferenceSVA;
import com.sva.vehicle.SkinMath;

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
 * Skins (design *1-*3, SkinMath / LiverySVA; config vehicles.textures / vehicles.liveries): every group is compiled as
 * one list per material tile (box-projected UVs, the tile bound before the call) plus, for the painted shell, the livery
 * lists - the side-projected overlay (sides / tops) and the decal faces. Everything goes through drawGroup(), so the
 * moving parts (renderPart / renderNacelle / renderPulse) and the cockpit clip passes (renderShellClipped) get the same
 * look. Both options off = no texture bound, the groups.txt colours: the original flat look.
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
        public static final int ROTOR = 0, TRAILER = 1, BORE = 2, NACELLE = 3,
                /** Stage E, the Singular Ship: the singularity's black sphere (pulses) and its gold rings + disk (spin). */
                CORE = 4, RINGS = 5,
                /** ★10 (vehicle/AnimParts): wheels (spin, steer, retract), landing gear legs (retract), control surfaces. */
                WHEEL = com.sva.vehicle.AnimParts.WHEEL, GEAR = com.sva.vehicle.AnimParts.GEAR, SURFACE = com.sva.vehicle.AnimParts.SURFACE,
                /** Cockpit stage 1: the cabin controls (steering wheels, cyclic, collective, yoke, throttle, pedals). */
                CONTROL = com.sva.vehicle.AnimParts.CONTROL;
        /** ★10: the animated part's data (wheel radius, steering, gear travel, surface control); null for the older parts. */
        public com.sva.vehicle.AnimParts.Def def;
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

        /** A ★10 part from its table entry (vehicle/AnimParts). */
        Part(com.sva.vehicle.AnimParts.Def d) {
            this(d.kind, d.name, d.groups, null, d.min, d.max, d.pivot, d.axis, 0F, 0F);
            def = d;
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
        // ship.obj (stage E): the singularity over the hull - the black sphere ("core", x -0.89..-0.20, y 1.72..2.41, centre
        // (-0.545, 2.065, 0); the holo-table's mini singularity at x 1.0..1.15 stays) pulses; its gold rings (x -1.52..0.42,
        // y 1.00..3.12) and the tilted accretion disk (x -1.36..0.26, y 1.81..2.31) spin about the vertical through it.
        // The y floor 1.15 keeps the gold trims along the hull (y 1.07..1.11) and the core's channel below in place.
        part("ship", new Part(Part.CORE, "sing", new String[] {"core"}, null, new float[] {-1.0F, 1.6F, -0.5F},
                new float[] {0.0F, 2.5F, 0.5F}, new float[] {-0.545F, 2.065F, 0F}, 1, 1F, 0F));
        part("ship", new Part(Part.RINGS, "rings", new String[] {"gold", "disk"}, null, new float[] {-1.6F, 1.15F, -0.9F},
                new float[] {0.5F, 3.2F, 0.9F}, new float[] {-0.545F, 2.065F, 0F}, 1, 1F, 0F));
        // ★10: wheels, gear legs and control surfaces (vehicle/AnimParts) go in FRONT of the parts above - the first part
        // that takes a triangle wins, so the truck's trailer wheels are wheels and not part of the plain trailer part
        String[] animated = {"buggy", "truck", "drill", "heli", "plane", "jet", "tilt", "shuttle", "trailer_tank", "trailer_platform",
                "trailer_charger"};
        for (String model : animated) {
            List<com.sva.vehicle.AnimParts.Def> defs = com.sva.vehicle.AnimParts.of(model);
            List<Part> l = PARTS.get(model);
            if (l == null) {
                l = new ArrayList<Part>();
                PARTS.put(model, l);
            }
            for (int i = 0; i < defs.size(); i++) {
                l.add(i, new Part(defs.get(i)));
            }
        }
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
    /** Material of each group (a part's "#name/material" -> material). */
    private final List<String> mats = new ArrayList<String>();
    /** groups.txt colours by material (0xRRGGBB), for the livery's accent colour. */
    private final Map<String, Integer> paletteRgb = new HashMap<String, Integer>();
    /** The main paint (paint, or white on a model without one) and the painted shell's side bbox {x0, y0, x1, y1}. */
    private String primary = "paint";
    private float[] paintBox;
    /** Compiled per group: base lists (one per tile; tile null = untextured), livery side / top / decal lists (0 = none). */
    private int[][] baseLists;
    private String[][] baseTiles;
    private int[] sideList, topList, decalList;
    /** The style render() / renderPart() draw with (style()): livery (-1 none) and board number. */
    private int livery = -1, board = 1;
    /**
     * ★10 / ★6, set per draw by the renderer (anim()): the tread texture's scroll (model units along x; 0 = still) and the
     * red 'tail' lamps' brightness (1 = as in groups.txt; dimmer while not braking).
     */
    private float treadShift, tailLevel = 1F;
    /**
     * Cockpit stage 2 (★8), set per draw by the renderer (cabin()): the 'cabin' plafonds' light 0..1 - 0 = off (drawn lit
     * by the world like any surface, a little dimmer), > 0 = full bright in the tier's colour `cabinRgb` at that
     * brightness; -1 = as in groups.txt (always glowing: config vehicles.nightLighting off).
     */
    private float cabinLevel = -1F;
    private int cabinRgb = 0xFFFFFF;
    /** colourOf() cache, rebuilt when the livery or the config changes. */
    private float[][] drawColours;
    private int drawKey = Integer.MIN_VALUE;
    private static final float[] WHITE = {1F, 1F, 1F};

    /** Wave 2 (★8): the OBJ's bounding box in model units (x forward, y up, z right) - where tuning parts go. */
    public final float[] bbMin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, bbMax = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

    /**
     * Wave 2 (★8): the roof line - the highest triangle over each of ROOF_BINS slices along x, counting only triangles that
     * reach into the middle of the width (|z| <= 55 % of the widest point: a flat roof's corners are at the sides, but its
     * triangles cross the middle; an antenna or mirror at the side doesn't lift it); the light bar sits on it.
     */
    public static final int ROOF_BINS = 48;
    private final float[] roof = new float[ROOF_BINS];
    /** The highest point of that middle strip (the body's height without side masts). */
    public float coreTop = -Float.MAX_VALUE;

    private void roofProfile() {
        java.util.Arrays.fill(roof, -Float.MAX_VALUE);
        float zMax = Math.max(Math.abs(bbMin[2]), Math.abs(bbMax[2])) * 0.55F, span = Math.max(1e-3F, bbMax[0] - bbMin[0]);
        for (List<float[]> tris : pending.values()) {
            for (int i = 0; i + 2 < tris.size(); i += 3) {
                float[] a = tris.get(i), b = tris.get(i + 1), c = tris.get(i + 2);
                if (Math.min(a[2], Math.min(b[2], c[2])) > zMax || Math.max(a[2], Math.max(b[2], c[2])) < -zMax) {
                    continue;
                }
                float top = Math.max(a[1], Math.max(b[1], c[1]));
                int b0 = bin(Math.min(a[0], Math.min(b[0], c[0])), span), b1 = bin(Math.max(a[0], Math.max(b[0], c[0])), span);
                for (int k = b0; k <= b1; k++) {
                    roof[k] = Math.max(roof[k], top);
                }
                coreTop = Math.max(coreTop, top);
            }
        }
        if (coreTop == -Float.MAX_VALUE) {
            coreTop = bbMax[1];
        }
    }

    private int bin(float x, float span) {
        return Math.max(0, Math.min(ROOF_BINS - 1, (int) ((x - bbMin[0]) / span * ROOF_BINS)));
    }

    /** The roof height (model units) at model x (the middle strip; the body's top where a slice is empty). */
    public float roofAt(float x) {
        int b = bin(x, Math.max(1e-3F, bbMax[0] - bbMin[0]));
        return roof[b] == -Float.MAX_VALUE ? coreTop : roof[b];
    }

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
                        paletteRgb.put(p[0], Integer.valueOf(Integer.parseInt(p[1]) << 16 | Integer.parseInt(p[2]) << 8 | Integer.parseInt(p[3])));
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
                        float[] q = floats(line);
                        v.add(q);
                        for (int k = 0; k < 3; k++) {
                            bbMin[k] = Math.min(bbMin[k], q[k]);         // wave 2: the bounding box (tuning parts)
                            bbMax[k] = Math.max(bbMax[k], q[k]);
                        }
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
                            boolean clipped = false;
                            for (Part pt : parts) {
                                if (pt.def != null && pt.def.cut) {
                                    // ★10 control surface: the triangles crossing the box are clipped by it - the piece
                                    // inside moves with the surface, the pieces outside stay
                                    if (!pt.def.takes(curName) || !pt.def.overlaps(poly[0], poly[i], poly[i + 1])) {
                                        continue;
                                    }
                                    List<float[][]> outside = new ArrayList<float[][]>();
                                    float[][] in = com.sva.vehicle.AnimParts.clipBox(new float[][] {poly[0], poly[i], poly[i + 1]},
                                            pt.def.min, pt.def.max, outside);
                                    if (in != null) {
                                        fan(in, partList("#" + pt.name + "/" + curName));
                                    }
                                    for (float[][] o : outside) {
                                        fan(o, cur);
                                    }
                                    clipped = true;
                                    break;
                                }
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
                            if (clipped) {
                                continue;
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
            roofProfile();
        } catch (Exception e) {
            cpw.mods.fml.common.FMLLog.warning("[SVA] model %s failed to load: %s", name, e);
            failed = true;
            return;
        }
        primary = palette.containsKey("paint") ? "paint" : "white";
        for (String g : pending.keySet()) {
            groups.add(g);
            String mat = g.startsWith("#") ? g.substring(g.indexOf('/') + 1) : g;     // a part keeps its material's colour
            mats.add(mat);
            if (SkinMath.paintable(mat)) {
                for (float[] p : pending.get(g)) {                                 // the painted shell's side bbox
                    if (paintBox == null) {
                        paintBox = new float[] {p[0], p[1], p[0], p[1]};
                    }
                    paintBox[0] = Math.min(paintBox[0], p[0]);
                    paintBox[1] = Math.min(paintBox[1], p[1]);
                    paintBox[2] = Math.max(paintBox[2], p[0]);
                    paintBox[3] = Math.max(paintBox[3], p[1]);
                }
            }
            float[] c = palette.get(mat);
            colors.add(c != null ? c : new float[] {0.6F, 0.6F, 0.6F});
            Boolean e = glow.get(mat);
            emissive.add(e != null ? e : Boolean.FALSE);
        }
    }

    private List<float[]> partList(String key) {
        List<float[]> l = pending.get(key);
        if (l == null) {
            l = new ArrayList<float[]>();
            pending.put(key, l);
        }
        return l;
    }

    /** A convex polygon as a triangle fan into `to`. */
    private static void fan(float[][] poly, List<float[]> to) {
        for (int k = 1; k + 1 < poly.length; k++) {
            to.add(poly[0]);
            to.add(poly[k]);
            to.add(poly[k + 1]);
        }
    }

    private static float[] floats(String line) {
        String[] p = line.trim().split("\\s+");
        return new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])};
    }

    /**
     * Compiles every group once: the base lists (triangles bucketed by SkinMath.tile from the face normal, UVs by box
     * projection along its dominant axis) and, for the painted shell, the livery lists: side faces (|ny| <= TOP_NY) and
     * top faces with the side-projected overlay UVs, and the decal faces (outer side faces, |nz| > SIDE_NZ, overlapping
     * the model's decal box) with decal UVs mirrored on the left side.
     */
    private void compile() {
        lists = 0;
        int n = groups.size();
        baseLists = new int[n][];
        baseTiles = new String[n][];
        sideList = new int[n];
        topList = new int[n];
        decalList = new int[n];
        float[] dbox = SkinMath.decalBox(name);
        for (int i = 0; i < n; i++) {
            List<float[]> tris = pending.get(groups.get(i));
            String mat = mats.get(i);
            int count = tris.size() / 3;
            float[][] fn = new float[count][];
            Map<String, List<Integer>> byTile = new LinkedHashMap<String, List<Integer>>();
            for (int t = 0; t < count; t++) {
                fn[t] = faceNormal(tris.get(t * 3), tris.get(t * 3 + 1), tris.get(t * 3 + 2));
                String tile = SkinMath.tile(mat, SkinMath.axis(fn[t][0], fn[t][1], fn[t][2]), fn[t][1], emissive.get(i).booleanValue());
                String key = tile == null ? "" : tile;
                List<Integer> l = byTile.get(key);
                if (l == null) {
                    l = new ArrayList<Integer>();
                    byTile.put(key, l);
                }
                l.add(Integer.valueOf(t));
            }
            baseLists[i] = new int[byTile.size()];
            baseTiles[i] = new String[byTile.size()];
            int k = 0;
            for (Map.Entry<String, List<Integer>> e : byTile.entrySet()) {
                baseTiles[i][k] = e.getKey().length() == 0 ? null : e.getKey();
                baseLists[i][k++] = compileList(tris, fn, e.getValue(), 0, null);
            }
            if (SkinMath.paintable(mat) && paintBox != null) {
                List<Integer> side = new ArrayList<Integer>(), top = new ArrayList<Integer>(), dec = new ArrayList<Integer>();
                for (int t = 0; t < count; t++) {
                    (Math.abs(fn[t][1]) <= SkinMath.TOP_NY ? side : top).add(Integer.valueOf(t));
                    if (dbox != null && onDecal(dbox, fn[t], tris.get(t * 3), tris.get(t * 3 + 1), tris.get(t * 3 + 2))) {
                        dec.add(Integer.valueOf(t));
                    }
                }
                sideList[i] = side.isEmpty() ? 0 : compileList(tris, fn, side, 1, paintBox);
                topList[i] = top.isEmpty() ? 0 : compileList(tris, fn, top, 1, paintBox);
                decalList[i] = dec.isEmpty() ? 0 : compileList(tris, fn, dec, 2, dbox);
            }
        }
        pending = null;
    }

    /** The unit face normal of a triangle, turned to agree with its smooth vertex normals (the winding is not reliable). */
    private static float[] faceNormal(float[] a, float[] b, float[] c) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float x = uy * vz - uz * vy, y = uz * vx - ux * vz, z = ux * vy - uy * vx;
        float sx = a[3] + b[3] + c[3], sy = a[4] + b[4] + c[4], sz = a[5] + b[5] + c[5];
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        if (l < 1e-9F) {                                    // degenerate: the averaged vertex normals
            x = sx;
            y = sy;
            z = sz;
            l = Math.max(1e-6F, (float) Math.sqrt(x * x + y * y + z * z));
        }
        float s = x * sx + y * sy + z * sz < 0 ? -1F : 1F;
        return new float[] {s * x / l, s * y / l, s * z / l};
    }

    /** An outer side face (looking away from the centre plane z = 0) that overlaps the decal box in x / y. */
    private static boolean onDecal(float[] box, float[] fn, float[] a, float[] b, float[] c) {
        if (Math.abs(fn[2]) <= SkinMath.SIDE_NZ || fn[2] * (a[2] + b[2] + c[2]) <= 0) {
            return false;
        }
        return Math.max(a[0], Math.max(b[0], c[0])) > box[0] && Math.min(a[0], Math.min(b[0], c[0])) < box[2]
                && Math.max(a[1], Math.max(b[1], c[1])) > box[1] && Math.min(a[1], Math.min(b[1], c[1])) < box[3];
    }

    /** One display list of the given triangles; uvMode 0 = tile (box projection), 1 = livery overlay, 2 = decal. */
    private static int compileList(List<float[]> tris, float[][] fn, List<Integer> which, int uvMode, float[] box) {
        int list = GL11.glGenLists(1);
        float[] uv = new float[2];
        GL11.glNewList(list, GL11.GL_COMPILE);
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (Integer ti : which) {
            int t = ti.intValue();
            int axis = SkinMath.axis(fn[t][0], fn[t][1], fn[t][2]);
            for (int k = 0; k < 3; k++) {
                float[] p = tris.get(t * 3 + k);
                if (uvMode == 0) {
                    SkinMath.uv(axis, p[0], p[1], p[2], uv);
                } else if (uvMode == 1) {
                    SkinMath.overlayUv(box, p[0], p[1], uv);
                } else {
                    SkinMath.decalUv(box, p[0], p[1], fn[t][2], uv);
                }
                GL11.glTexCoord2f(uv[0], uv[1]);
                GL11.glNormal3f(p[3], p[4], p[5]);
                GL11.glVertex3f(p[0], p[1], p[2]);
            }
        }
        GL11.glEnd();
        GL11.glEndList();
        return list;
    }

    /** Sets the skin the next draws use: livery (SkinMath.WORK.., -1 none) and the board number on the side decal. */
    public void style(int livery, int board) {
        this.livery = livery;
        this.board = board;
    }

    /** ★10 / ★6: the tread scroll and the tail lamps' brightness for the next draws (reset with anim(0, 1)). */
    public void anim(float treadShift, float tailLevel) {
        this.treadShift = treadShift;
        this.tailLevel = tailLevel;
    }

    /** ★8: the plafonds for the next draws (level -1 = always lit as before; reset with cabin(-1, 0)). */
    public void cabin(float level, int rgb) {
        this.cabinLevel = level;
        this.cabinRgb = rgb;
    }

    private boolean liveried() {
        return ConfigSVA.liveries && livery >= 0;
    }

    /** ★7 the trim's livery (config vehicles.interiorTrim, independent of vehicles.liveries), -1 off. */
    private int trimLivery() {
        return ConfigSVA.interiorTrim && livery >= 0 ? livery : -1;
    }

    /** ★15: the tanker's 'band' in its cargo's colour for the next draws (-1: groups.txt's; reset with band(-1)). */
    private int bandRgb = -1;

    public void band(int rgb) {
        bandRgb = rgb;
    }

    /** A group's draw colour: the livery's repaint, white under tiles with their own colours, else groups.txt. */
    private float[] colourOf(int i) {
        if (bandRgb >= 0 && "band".equals(mats.get(i))) {
            return new float[] {(bandRgb >> 16 & 255) / 255F, (bandRgb >> 8 & 255) / 255F, (bandRgb & 255) / 255F};
        }
        int key = ((liveried() ? livery : -1) * 2 + (ConfigSVA.textures ? 1 : 0)) * 8 + trimLivery() + 1;
        if (drawColours == null || key != drawKey) {
            drawColours = new float[groups.size()][];
            for (int g = 0; g < groups.size(); g++) {
                float[] c = colors.get(g);
                String mat = mats.get(g);
                if (ConfigSVA.textures && SkinMath.ownColours(mat)) {
                    c = WHITE;
                } else if (liveried()) {
                    int base = rgb(c);
                    int rgb = SkinMath.paintColour(livery, mat, primary, base);
                    if (rgb != base) {
                        c = new float[] {(rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F};
                    }
                }
                if (trimLivery() >= 0 && SkinMath.trimRole(mat) >= 0) {
                    // ★7: the cabin trim's colour by tier (seats, floor, inserts, roof lining)
                    int rgb = SkinMath.trimColour(trimLivery(), mat, rgb(c));
                    c = new float[] {(rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F};
                }
                drawColours[g] = c;
            }
            drawKey = key;
        }
        return drawColours[i];
    }

    private static int rgb(float[] c) {
        return Math.round(c[0] * 255) << 16 | Math.round(c[1] * 255) << 8 | Math.round(c[2] * 255);
    }

    /** Calls a group's base lists, each with its tile bound (textured) or with texturing off; leaves texturing off. */
    private void callBase(int i, boolean textured) {
        int[] l = baseLists[i];
        String[] t = baseTiles[i];
        int lv = liveried() ? livery : -1;
        String mat = mats.get(i);
        int tl = trimLivery();
        boolean scroll = textured && treadShift != 0F && "tread".equals(mats.get(i));
        if (scroll) {
            // ★10: the crawler's tread runs - its box UVs have u = x (1 tile per model unit) on the sides and the top,
            // so moving the texture against x makes the links travel forward along the top run
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPushMatrix();
            GL11.glTranslatef(-treadShift, 0F, 0F);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }
        for (int k = 0; k < l.length; k++) {
            if (textured && t[k] != null) {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                LiverySVA.bindTile(SkinMath.trimTile(tl, mat, SkinMath.tileFor(lv, t[k])));
            } else {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
            }
            GL11.glCallList(l[k]);
        }
        if (scroll) {
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    /**
     * The livery layers over a painted group (already drawn): the lit overlay (stripe / hazard band) on its side faces,
     * the side decal, the glowing lines (III / Singular, full bright unless a wreck) on sides and tops. Blended over the
     * base at the same depth (polygon offset, no depth writes); the texels are fully opaque or clear, so the cockpit clip
     * passes that draw a face twice change nothing.
     */
    private void drawLivery(int i, float shade) {
        String mat = mats.get(i);
        int civil = SkinMath.civilStripe(paletteRgb);
        int matRgb = rgb(colors.get(i));
        int ov = sideList[i] != 0 ? LiverySVA.overlay(name, livery, mat, primary, paintBox, civil, matRgb, false) : 0;
        int gl = sideList[i] != 0 || topList[i] != 0 ? LiverySVA.overlay(name, livery, mat, primary, paintBox, civil, matRgb, true) : 0;
        int dc = decalList[i] != 0 ? LiverySVA.decal(board, SkinMath.decalColour(livery, civil)) : 0;
        if (ov == 0 && gl == 0 && dc == 0) {
            return;
        }
        boolean bright = shade >= 1F && SkinMath.glowColour(livery) >= 0;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_POLYGON_BIT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(-1F, -4F);
        GL11.glColor4f(shade, shade, shade, 1F);
        if (ov != 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, ov);
            GL11.glCallList(sideList[i]);
        }
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        if (bright) {
            GL11.glDisable(GL11.GL_LIGHTING);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        }
        if (dc != 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, dc);
            GL11.glCallList(decalList[i]);
        }
        if (gl != 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, gl);
            if (sideList[i] != 0) {
                GL11.glCallList(sideList[i]);
            }
            if (topList[i] != 0) {
                GL11.glCallList(topList[i]);
            }
        }
        if (bright) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
        }
        GL11.glDepthMask(true);
        GL11.glPopAttrib();
    }

    /** Glass groups: drawn after the opaque ones, see-through. */
    public static boolean isGlass(String group) {
        return com.sva.vehicle.CockpitView.isGlass(group);       // shared with the server-side view check
    }

    /** Glass alpha and how far its colour is lifted toward white (the OBJ colour is a dark tint). */
    public static final float GLASS_ALPHA = 0.28F, GLASS_LIGHTEN = 0.55F;

    /**
     * Body shell groups (the painted hull: paint, paint2, paint3, white and the shuttle's lit side windows; tiles / nb
     * kept for future models): the cockpit-window clip (render's windows) cuts only these. Dashboards / consoles
     * (dark), seats, screens, steering wheels, levers and the other glowing parts are never cut.
     */
    public static boolean isShell(String group) {
        return com.sva.vehicle.CockpitView.isShell(group);       // shared with the server-side view check
    }

    /** Glass alpha seen from the own cockpit (lighter than outside: the view through the windscreen stays clear). */
    public static final float COCKPIT_GLASS_ALPHA = 0.15F;

    public void render(float shade) {
        render(shade, GLASS_ALPHA, 1F, null);
    }

    public void render(float shade, boolean withGlass) {
        render(shade, withGlass ? GLASS_ALPHA : 0F, 1F, null);
    }

    /**
     * Draws the opaque groups, then the glass / canopy groups translucent with `glassAlpha` (0: left out): blended,
     * tinted lighter, no depth writes, both faces (texture 2D must already be off). `shade` darkens everything (a
     * wreck: no glow).
     * windows (the local rider's first-person view, VehicleType.cabWindows): boxes {x0, x1, y0, y1, z0, z1} in model
     * units - the body shell (isShell) is drawn opaque everywhere EXCEPT inside them: the cockpit window openings, so
     * the world shows only through the windscreen / canopy (see renderShellClipped).
     * shellAlpha < 1 (the config fallback vehicles.seeThroughHull): the whole shell is drawn after the opaque groups,
     * lit, blended with that alpha and without depth writes (the older see-through look).
     */
    public void render(float shade, float glassAlpha, float shellAlpha, float[][] windows) {
        if (lists < 0) {
            compile();
        }
        boolean seeThrough = shellAlpha < 1F;
        boolean clip = !seeThrough && windows != null && windows.length > 0;
        for (int i = 0; i < groups.size(); i++) {
            String g = groups.get(i);
            if (isGlass(g) || g.startsWith("#") || (seeThrough || clip) && isShell(g)) {
                continue;
            }
            drawGroup(i, shade);
        }
        if (clip) {
            renderShellClipped(shade, windows);
        }
        if (seeThrough) {
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            for (int i = 0; i < groups.size(); i++) {
                if (!isShell(groups.get(i))) {
                    continue;
                }
                float[] c = colourOf(i);
                GL11.glColor4f(c[0] * shade, c[1] * shade, c[2] * shade, shellAlpha);
                callBase(i, ConfigSVA.textures);
            }
            GL11.glDepthMask(true);
            GL11.glPopAttrib();
        }
        if (glassAlpha > 0F) {
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            for (int i = 0; i < groups.size(); i++) {
                if (!isGlass(groups.get(i))) {
                    continue;
                }
                float[] c = colourOf(i);
                GL11.glColor4f(lighten(c[0]) * shade, lighten(c[1]) * shade, lighten(c[2]) * shade, glassAlpha);
                callBase(i, ConfigSVA.textures);
            }
            GL11.glDepthMask(true);
            GL11.glPopAttrib();
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /**
     * The shell minus the window boxes, with user clip planes given in model space (after the model transform; GL keeps
     * them in eye space). Outside one box = the union of its (up to 6) outer half-spaces; outside all boxes = the
     * intersection over the boxes. So: one pass per combination of one outer half-space of every box, clip plane b
     * (GL_CLIP_PLANE0 + b) holding box b's chosen half-space - 6 passes for one box, 36 for two. Passes overlap (a point
     * outside on two sides is drawn twice): the same geometry at the same depth (GL_LEQUAL) and colour - harmless.
     */
    private static final java.nio.DoubleBuffer CLIP_EQ = org.lwjgl.BufferUtils.createDoubleBuffer(4);

    private void renderShellClipped(float shade, float[][] windows) {
        int n = Math.min(windows.length, com.sva.vehicle.CockpitView.MAX_WINDOWS);
        double[][][] planes = new double[n][][];
        for (int b = 0; b < n; b++) {
            planes[b] = com.sva.vehicle.CockpitView.outerPlanes(windows[b]);
            if (planes[b].length == 0) {
                return;                                         // an endless box: no shell outside it
            }
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_TRANSFORM_BIT);
        int[] pick = new int[n];
        java.nio.DoubleBuffer buf = CLIP_EQ;
        while (true) {
            for (int b = 0; b < n; b++) {
                buf.clear();
                buf.put(planes[b][pick[b]]).flip();
                GL11.glClipPlane(GL11.GL_CLIP_PLANE0 + b, buf);
                GL11.glEnable(GL11.GL_CLIP_PLANE0 + b);
            }
            for (int i = 0; i < groups.size(); i++) {
                if (isShell(groups.get(i))) {
                    drawGroup(i, shade);
                }
            }
            int b = 0;                                          // the next combination (odometer)
            while (b < n && ++pick[b] >= planes[b].length) {
                pick[b] = 0;
                b++;
            }
            if (b >= n) {
                break;
            }
        }
        for (int b = 0; b < n; b++) {
            GL11.glDisable(GL11.GL_CLIP_PLANE0 + b);
        }
        GL11.glPopAttrib();
    }

    private static float lighten(float c) {
        return c + (1F - c) * GLASS_LIGHTEN;
    }

    /** One opaque group: lit and shaded, or (emissive, not a wreck) full bright; then its livery layers. */
    private void drawGroup(int i, float shade) {
        float[] c = colourOf(i);
        boolean plafond = cabinLevel >= 0F && "cabin".equals(mats.get(i));
        if (plafond && cabinLevel <= 0.01F) {
            // ★8: a plafond that is off - an ordinary lit surface, a little greyer than its glowing colour
            GL11.glColor4f(c[0] * shade * 0.7F, c[1] * shade * 0.7F, c[2] * shade * 0.7F, 1F);
            callBase(i, ConfigSVA.textures);
        } else if (plafond && shade >= 1F) {
            // ★8: on at night - full bright, half way to the tier's colour, as bright as the level
            float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
            GL11.glDisable(GL11.GL_LIGHTING);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            float k = 0.35F + 0.65F * Math.min(1F, cabinLevel);
            float tr = (cabinRgb >> 16 & 255) / 255F, tg = (cabinRgb >> 8 & 255) / 255F, tb = (cabinRgb & 255) / 255F;
            GL11.glColor4f((c[0] + tr) * 0.5F * k, (c[1] + tg) * 0.5F * k, (c[2] + tb) * 0.5F * k, 1F);
            callBase(i, ConfigSVA.textures);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
            GL11.glEnable(GL11.GL_LIGHTING);
        } else if (emissive.get(i).booleanValue() && shade >= 1F) {
            float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
            GL11.glDisable(GL11.GL_LIGHTING);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            float lv = tailLevel != 1F && "tail".equals(mats.get(i)) ? tailLevel : 1F;   // ★6: brake lights
            GL11.glColor4f(c[0] * lv, c[1] * lv, c[2] * lv, 1F);
            callBase(i, ConfigSVA.textures);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
            GL11.glEnable(GL11.GL_LIGHTING);
        } else {
            GL11.glColor4f(c[0] * shade, c[1] * shade, c[2] * shade, 1F);
            callBase(i, ConfigSVA.textures);
        }
        if (liveried() && SkinMath.paintable(mats.get(i))) {
            drawLivery(i, shade);
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

    /** A CORE part scaled by `scale` about its pivot (the singularity's pulse). */
    public void renderPulse(Part p, float scale, float shade) {
        GL11.glPushMatrix();
        GL11.glTranslatef(p.pivot[0], p.pivot[1], p.pivot[2]);
        GL11.glScalef(scale, scale, scale);
        GL11.glTranslatef(-p.pivot[0], -p.pivot[1], -p.pivot[2]);
        renderPart(p, 0F, shade);
        GL11.glPopMatrix();
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
            callBase(i, false);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }
}
