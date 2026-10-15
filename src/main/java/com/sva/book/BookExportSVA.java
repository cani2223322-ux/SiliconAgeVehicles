package com.sva.book;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sva.airport.PlannerMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Wave C, item 48: one content source for the book and the website - this writes the handbook as web content from the same
 * text files the game reads, resolved against THIS game's data (run on a server: -Dsva.bookexport=DIR, after the recipes are
 * registered; the self-test runs it into a temporary folder and parses it back):
 * <ul>
 * <li>book.json - both languages: the chapters and their blocks, with a vehicle's numbers (BookResolveSVA), each recipe's grid
 * (the registered recipe: items by registry name / meta / unlocalized name and their ru / en names), the schematics' block lists,
 * the checklists' items, the tutorial's steps;</li>
 * <li>schem/ID.png and schem/ID_L1.png ... - the isometric renders, layer by layer, drawn from the same cells the book draws
 * (BookSchematics, the block textures out of the jar); schem/airport.png - the runway plan;</li>
 * <li>img/NAME.png - the book's pictures.</li>
 * </ul>
 * The addon's tools/book_export/book_html.py turns it into HTML fragments (ru with data-en English), the site's
 * build_vehicles_book.py into the «Справочник Vehicles» page.
 */
public final class BookExportSVA {

    /** The export format's version (the site checks it). */
    public static final int FORMAT = 1;

    private BookExportSVA() {
    }

    /** Writes everything into `dir`. @return the files written */
    public static List<File> export(File dir) throws Exception {
        List<File> out = new ArrayList<File>();
        exportDir = dir;
        new File(dir, "schem").mkdirs();
        new File(dir, "img").mkdirs();
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("mod", com.sva.ReferenceSVA.VERSION);
        JsonArray order = new JsonArray();
        for (String id : BookMarkup.index()) {
            order.add(new JsonPrimitive(id));
        }
        root.add("chapters", order);
        JsonObject langs = new JsonObject();
        java.util.Set<String> imgs = new java.util.TreeSet<String>();
        for (String lang : BookMarkup.LANGS) {
            langs.add(lang, lang(lang, imgs));
        }
        root.add("langs", langs);
        // schematics: the renders and the lists (names in both languages)
        JsonObject sc = new JsonObject();
        for (String id : BookSchematics.IDS) {
            BookSchematics.Schem s = BookSchematics.get(id, 0);
            JsonObject o = new JsonObject();
            o.addProperty("plan", s.plan);
            o.addProperty("ru", BookLang.tr("ru", "sva.book.schem." + id));
            o.addProperty("en", BookLang.tr("en", "sva.book.schem." + id));
            int[] layers = s.layers();
            o.addProperty("layers", s.plan ? 1 : layers.length);
            o.addProperty("cells", s.cells.size());
            JsonArray list = new JsonArray();
            for (BookSchematics.Entry e : s.list) {
                JsonObject li = stackJson(e.item);
                li.addProperty("count", e.count);
                li.addProperty("auto", e.auto);
                if (e.key.length() > 0) {
                    li.addProperty("ru", BookLang.tr("ru", e.key));
                    li.addProperty("en", BookLang.tr("en", e.key));
                }
                list.add(li);
            }
            o.add("list", list);
            File f = new File(dir, "schem/" + id + ".png");
            ImageIO.write(s.plan ? plan(s) : iso(s, -1), "png", f);
            out.add(f);
            if (!s.plan && layers.length > 1) {
                for (int k = 0; k < layers.length; k++) {
                    File fl = new File(dir, "schem/" + id + "_L" + (k + 1) + ".png");
                    ImageIO.write(iso(s, k), "png", fl);
                    out.add(fl);
                }
            }
            sc.add(id, o);
        }
        root.add("schematics", sc);
        for (String name : imgs) {
            InputStream in = BookExportSVA.class.getResourceAsStream("/assets/siliconagevehicles/textures/book/" + name + ".png");
            if (in != null) {
                File f = new File(dir, "img/" + name + ".png");
                FileOutputStream o = new FileOutputStream(f);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                }
                o.close();
                in.close();
                out.add(f);
            }
        }
        File jf = new File(dir, "book.json");
        Writer w = new OutputStreamWriter(new FileOutputStream(jf), "UTF-8");
        w.write(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root));
        w.close();
        out.add(0, jf);
        return out;
    }

    /** One language: its chapters, their blocks resolved; the pages of the reference layout. */
    static JsonObject lang(String lang, java.util.Set<String> imgs) {
        BookDoc d = BookMarkup.get(lang);
        BookResolveSVA res = new BookResolveSVA(BookResolveSVA.files(lang));
        BookLayout L = BookLayout.layout(d, BookLayout.ESTIMATE, res, REF_W, REF_H);
        JsonObject o = new JsonObject();
        o.addProperty("pages", L.pages.size());
        JsonArray chs = new JsonArray();
        for (int ci = 0; ci < d.chapters.size(); ci++) {
            BookDoc.Chapter c = d.chapters.get(ci);
            JsonObject co = new JsonObject();
            co.addProperty("id", c.id);
            co.addProperty("title", c.title);
            co.addProperty("icon", c.icon);
            co.addProperty("page", L.chapterStart[ci] + 1);
            JsonArray bl = new JsonArray();
            for (BookDoc.Block b : c.blocks) {
                JsonObject bo = new JsonObject();
                bo.addProperty("k", BookDoc.KIND_NAMES[b.kind]);
                if (b.text.length() > 0) {
                    bo.addProperty("text", b.text);
                }
                if (b.arg.length() > 0) {
                    bo.addProperty("arg", b.arg);
                }
                switch (b.kind) {
                    case BookDoc.VEHICLE: {
                        JsonArray rows = new JsonArray();
                        for (String[] r : res.vehicleRows(b.arg)) {
                            JsonArray row = new JsonArray();
                            row.add(new JsonPrimitive(r[0]));
                            row.add(new JsonPrimitive(r[1]));
                            rows.add(row);
                        }
                        bo.add("rows", rows);
                        break;
                    }
                    case BookDoc.MODEL: {
                        VehicleType t = BookResolveSVA.type(b.arg);
                        bo.addProperty("model", t == null ? "" : t.key);
                        break;
                    }
                    case BookDoc.RECIPE:
                        bo.add("recipe", recipe(b.arg, lang));
                        break;
                    case BookDoc.IMG:
                        imgs.add(b.arg);
                        break;
                    case BookDoc.CHECK: {
                        int list = BookChecklists.byId(b.arg);
                        bo.addProperty("title", BookLang.tr(lang, "sva.book.chk." + b.arg));
                        JsonArray items = new JsonArray();
                        for (int i = 0; list >= 0 && i < BookChecklists.size(list); i++) {
                            String[] p = BookLang.tr(lang, BookChecklists.key(list, i)).split("\\|");
                            JsonArray it = new JsonArray();
                            it.add(new JsonPrimitive(p[0]));
                            it.add(new JsonPrimitive(p.length > 1 ? p[1] : ""));
                            items.add(it);
                        }
                        bo.add("items", items);
                        break;
                    }
                    case BookDoc.TUTORIAL: {
                        bo.addProperty("title", BookLang.tr(lang, "sva.book.tut.title"));
                        JsonArray steps = new JsonArray();
                        for (String k : TutorialMath.STEPS) {
                            JsonArray st = new JsonArray();
                            st.add(new JsonPrimitive(BookLang.tr(lang, "sva.book.tut." + k)));
                            st.add(new JsonPrimitive(BookLang.tr(lang, "sva.book.tut." + k + ".hint")));
                            steps.add(st);
                        }
                        bo.add("steps", steps);
                        break;
                    }
                    default:
                        break;
                }
                bl.add(bo);
            }
            co.add("blocks", bl);
            chs.add(co);
        }
        o.add("chapters", chs);
        return o;
    }

    /** The reference page size (the book at its full size: 560 x 330 GUI px). */
    public static final int REF_W = 259, REF_H = 278;

    /** A recipe as JSON: kind, grid size, cells (null or an item / a list's first), the output, the workbench's data. */
    static JsonObject recipe(String ref, String lang) {
        JsonObject o = new JsonObject();
        BookRecipesSVA.Grid g = BookRecipesSVA.resolve(ref);
        if (g == null) {
            o.addProperty("missing", true);
            return o;
        }
        o.addProperty("kind", g.workbench ? "wb" : "craft");
        o.addProperty("w", g.w);
        o.addProperty("shapeless", g.shapeless);
        JsonArray cells = new JsonArray();
        for (Object c : g.cells) {
            ItemStack s = preferred(c);
            cells.add(s == null ? com.google.gson.JsonNull.INSTANCE : stackJson(s));
        }
        o.add("cells", cells);
        JsonObject out = stackJson(g.out);
        out.addProperty("count", g.out.stackSize);
        o.add("out", out);
        if (g.workbench) {
            o.addProperty("eu", g.eu);
            o.addProperty("ticks", g.ticks);
            o.addProperty("tier", g.tier);
        }
        o.addProperty("caption", g.workbench ? BookLang.tr(lang, "sva.book.gui.wb", g.tier)
                : BookLang.tr(lang, g.shapeless ? "sva.book.gui.craftShapeless" : "sva.book.gui.craft"));
        return o;
    }

    /**
     * A cell's item for the site (one picture, no cycling): of an ore name's list the Silicon Age item first (the addon's,
     * then the core's, then vanilla) - the same preference as the workbench's displayOf; other mods' ones only when alone.
     */
    static ItemStack preferred(Object c) {
        if (!(c instanceof List)) {
            return BookRecipesSVA.pick(c, 0);
        }
        ItemStack best = null;
        int rank = 99;
        for (Object o : (List<?>) c) {
            if (!(o instanceof ItemStack) || ((ItemStack) o).getItem() == null) {
                continue;
            }
            Object reg = Item.itemRegistry.getNameForObject(((ItemStack) o).getItem());
            String mod = reg == null ? "" : reg.toString().split(":")[0];
            int r = "SiliconAgeVehicles".equals(mod) ? 0 : "SiliconAge".equals(mod) ? 1 : "minecraft".equals(mod) ? 2 : 3;
            if (r < rank) {
                rank = r;
                best = (ItemStack) o;
            }
        }
        return best == null ? null : BookRecipesSVA.pick(java.util.Collections.singletonList(best), 0);
    }

    /**
     * The addon's own icon texture of an item, for the site where its wiki has no picture yet: the item's texture file
     * (textures/items/NAME.png or textures/blocks/NAME.png), by the unlocalized name's tail, the registry name, or ITEM_TEX;
     * copied into DIR/items/. null when none.
     */
    static String itemTex(ItemStack s, String unloc, String reg) {
        String tail = unloc.substring(unloc.lastIndexOf('.') + 1);
        String name = reg.contains(":") ? reg.substring(reg.indexOf(':') + 1) : reg;
        String prefix = name.replaceAll("[A-Z].*$", "");
        String mapped = ITEM_TEX.get(unloc);
        String[] cands = {mapped, "items/" + tail, "items/" + name, "items/" + prefix + Character.toUpperCase(tail.charAt(0)) + tail.substring(1),
            "blocks/" + tail, "blocks/" + name};
        for (String c : cands) {
            if (c != null && BookExportSVA.class.getResource("/assets/siliconagevehicles/textures/" + c + ".png") != null) {
                return c;
            }
        }
        return null;
    }

    /** Items whose icon file is not found by the name rules (their ItemBlock's registerIcons). */
    static final Map<String, String> ITEM_TEX = new HashMap<String, String>();

    static {
        ITEM_TEX.put("tile.siliconagevehicles.tower", "items/aptTower");
        ITEM_TEX.put("tile.siliconagevehicles.runwayEnd", "blocks/runwayEnd_top");
        ITEM_TEX.put("tile.siliconagevehicles.supercap", "blocks/supercapTop");
        ITEM_TEX.put("tile.siliconagevehicles.taxiLight.centre", "blocks/taxi_centre");
    }

    /** The export folder (set while exporting: the item icons are copied into it). */
    private static File exportDir;

    static JsonObject stackJson(String ref) {
        ItemStack s = BookRecipesSVA.item(ref);
        if (s == null) {
            JsonObject o = new JsonObject();
            o.addProperty("reg", ref);
            return o;
        }
        return stackJson(s);
    }

    /** An item: registry name, meta, unlocalized name, the ru / en names (the lang files; the server's own name as the fallback). */
    static JsonObject stackJson(ItemStack s) {
        JsonObject o = new JsonObject();
        Object reg = Item.itemRegistry.getNameForObject(s.getItem());
        o.addProperty("reg", reg == null ? "" : reg.toString());
        o.addProperty("meta", s.getItemDamage());
        String un = s.getItem().getUnlocalizedName(s);
        o.addProperty("unloc", un == null ? "" : un);
        String key = un + ".name";
        String fallback;
        try {
            fallback = s.getDisplayName();
        } catch (Throwable t) {
            fallback = un;
        }
        o.addProperty("ru", BookLang.has("ru", key) ? BookLang.tr("ru", key) : fallback);
        o.addProperty("en", BookLang.has("en", key) ? BookLang.tr("en", key) : fallback);
        String r = reg == null ? "" : reg.toString();
        if (r.startsWith(com.sva.ReferenceSVA.MODID + ":") && un != null) {
            String tex = itemTex(s, un, r);
            if (tex != null) {
                o.addProperty("tex", tex + ".png");
                copyTex(tex);
            }
        }
        return o;
    }

    static void copyTex(String tex) {
        if (exportDir == null) {
            return;
        }
        File f = new File(exportDir, tex + ".png");
        if (f.isFile()) {
            return;
        }
        f.getParentFile().mkdirs();
        try {
            InputStream in = BookExportSVA.class.getResourceAsStream("/assets/siliconagevehicles/textures/" + tex + ".png");
            if (in == null) {
                return;
            }
            FileOutputStream o = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
            }
            o.close();
            in.close();
        } catch (java.io.IOException e) {
            // no icon: the site shows the name
        }
    }

    // ---------------------------------------------------------------- the renders

    private static final Map<String, BufferedImage> TEX = new HashMap<String, BufferedImage>();

    static BufferedImage tex(String name) {
        if (TEX.containsKey(name)) {
            return TEX.get(name);
        }
        BufferedImage im = null;
        try {
            InputStream in = BookExportSVA.class.getResourceAsStream("/assets/siliconagevehicles/textures/blocks/" + name + ".png");
            if (in != null) {
                im = ImageIO.read(in);
                in.close();
                if (im != null && im.getHeight() > im.getWidth()) {
                    im = im.getSubimage(0, 0, im.getWidth(), im.getWidth());       // an animated strip: its first frame
                }
            }
        } catch (Exception e) {
            im = null;
        }
        TEX.put(name, im);
        return im;
    }

    /** The isometric render (the book's projection, BookDrawSVA): 16 px tile half width, layers up to `upTo` (-1 all). */
    public static BufferedImage iso(BookSchematics.Schem s, int upTo) {
        int[] layers = s.layers();
        int maxY = upTo < 0 ? Integer.MAX_VALUE : layers[upTo], curY = upTo < 0 ? Integer.MIN_VALUE : layers[upTo];
        int[] b = s.bounds();
        float k = 16F, vh = 1.15F;
        float minX = (b[0] - (b[5] + 1)) * k, maxX = ((b[3] + 1) - b[2]) * k;
        float minY = ((b[0] + b[2]) * 0.5F - (b[4] + 1) * vh) * k, maxY2 = ((b[3] + 1 + b[5] + 1) * 0.5F - b[1] * vh) * k;
        int W = (int) (maxX - minX) + 24, H = (int) (maxY2 - minY) + 24;
        final float ox = 12 - minX, oy = 12 - minY;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        List<BookSchematics.Cell> cells = new ArrayList<BookSchematics.Cell>(s.cells);
        Collections.sort(cells, new Comparator<BookSchematics.Cell>() {
            public int compare(BookSchematics.Cell a, BookSchematics.Cell c) {
                int d = (a.x + a.z) - (c.x + c.z);
                return d != 0 ? d : a.y - c.y;
            }
        });
        for (BookSchematics.Cell c : cells) {
            if (c.y > maxY) {
                continue;
            }
            float dim = upTo >= 0 && c.y < curY ? 0.55F : 1F;
            float hh = c.role == BookSchematics.PAD ? 0.125F : 1F;
            BufferedImage top = tex(c.top), side = tex(c.side);
            // +z face: origin (x, y, z+1), u along +x, v down (-y); +x face: origin (x+1, y, z+1), u along -z; top: origin (x, y+hh, z)
            face(g, side, ox, oy, k, vh, new float[] {c.x, c.y + hh, c.z + 1}, new float[] {1, 0, 0}, new float[] {0, -hh, 0}, 0.62F * dim, hh);
            face(g, side, ox, oy, k, vh, new float[] {c.x + 1, c.y + hh, c.z + 1}, new float[] {0, 0, -1}, new float[] {0, -hh, 0}, 0.8F * dim, hh);
            face(g, top, ox, oy, k, vh, new float[] {c.x, c.y + hh, c.z}, new float[] {1, 0, 0}, new float[] {0, 0, 1}, dim, 1F);
            if (c.role == BookSchematics.MASTER) {
                g.setColor(new Color(64, 216, 255));
                g.setStroke(new java.awt.BasicStroke(2F));
                float[][] t = {{c.x, c.z}, {c.x + 1, c.z}, {c.x + 1, c.z + 1}, {c.x, c.z + 1}};
                int[] xs = new int[4], ys = new int[4];
                for (int i = 0; i < 4; i++) {
                    xs[i] = Math.round(ox + (t[i][0] - t[i][1]) * k);
                    ys[i] = Math.round(oy + ((t[i][0] + t[i][1]) * 0.5F - (c.y + hh) * vh) * k);
                }
                g.drawPolygon(xs, ys, 4);
            }
        }
        g.dispose();
        return img;
    }

    /** A textured parallelogram face: its corner `p0`, the edge vectors u (the texture's x) and v (its y), shaded. */
    static void face(Graphics2D g, BufferedImage t, float ox, float oy, float k, float vh, float[] p0, float[] u, float[] v, float shade, float vShare) {
        float[] o = proj(p0, ox, oy, k, vh);
        float[] pu = proj(new float[] {p0[0] + u[0], p0[1] + u[1], p0[2] + u[2]}, ox, oy, k, vh);
        float[] pv = proj(new float[] {p0[0] + v[0], p0[1] + v[1], p0[2] + v[2]}, ox, oy, k, vh);
        int tw = t == null ? 16 : t.getWidth(), th = t == null ? 16 : t.getHeight();
        int srcH = Math.max(1, Math.round(th * vShare));
        AffineTransform at = new AffineTransform((pu[0] - o[0]) / tw, (pu[1] - o[1]) / tw, (pv[0] - o[0]) / srcH, (pv[1] - o[1]) / srcH, o[0], o[1]);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.transform(at);
        if (t != null) {
            g2.drawImage(t.getSubimage(0, th - srcH, tw, srcH), 0, 0, null);
        } else {
            g2.setColor(new Color(150, 150, 150));
            g2.fillRect(0, 0, tw, srcH);
        }
        if (shade < 0.999F) {
            g2.setColor(new Color(0, 0, 0, Math.round((1 - shade) * 255)));
            g2.fillRect(0, 0, tw, srcH);
        }
        g2.dispose();
    }

    static float[] proj(float[] p, float ox, float oy, float k, float vh) {
        return new float[] {ox + (p[0] - p[2]) * k, oy + ((p[0] + p[2]) * 0.5F - p[1] * vh) * k};
    }

    /** The runway plan (BookDrawSVA.plan's look): 4 px a block along, 6 across. */
    public static BufferedImage plan(BookSchematics.Schem s) {
        double a0 = -100, a1 = BookSchematics.RWY_LEN + PlannerMath.LOC_BEYOND + 14, c = 24;
        double kx = 4, kz = 8;
        int W = (int) ((a1 - a0) * kx), H = (int) (2 * c * kz);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(60, 90, 52));
        g.fillRect(0, 0, W, H);
        double cy = H / 2.0, w2 = BookSchematics.RWY_WIDTH / 2.0;
        g.setColor(new Color(70, 72, 76));
        g.fillRect((int) ((0 - a0) * kx), (int) (cy - w2 * kz), (int) (BookSchematics.RWY_LEN * kx), (int) (2 * w2 * kz));
        g.setColor(new Color(232, 232, 232));
        for (double a = 4; a < BookSchematics.RWY_LEN - 4; a += 10) {
            g.fillRect((int) ((a - a0) * kx), (int) cy - 1, (int) (5 * kx), 2);
        }
        g.setColor(new Color(60, 208, 90));
        g.fillRect((int) ((-1 - a0) * kx), (int) (cy - w2 * kz), (int) kx, (int) (2 * w2 * kz));
        for (BookSchematics.Cell cl : s.cells) {
            if (cl.box == null) {
                continue;
            }
            int kind = cl.planKind;
            Color col = kind == PlannerMath.LOC_CRIT || kind == PlannerMath.GS_CRIT ? new Color(255, 178, 38, 130)
                    : kind == PlannerMath.LOC_SENS || kind == PlannerMath.GS_SENS ? new Color(255, 242, 102, 60)
                    : kind == PlannerMath.ALS || kind == PlannerMath.EDGE ? new Color(255, 255, 204, 220) : new Color(90, 216, 255, 230);
            double x0 = Math.max(a0, cl.box[0]), x1 = Math.min(a1, cl.box[1]);
            if (x1 < a0 || x0 > a1) {
                continue;
            }
            int px = (int) ((x0 - a0) * kx), pw = Math.max(3, (int) ((x1 - x0) * kx)), py = (int) (cy + Math.max(-c, cl.box[2]) * kz),
                    ph = Math.max(3, (int) ((Math.min(c, cl.box[3]) - Math.max(-c, cl.box[2])) * kz));
            g.setColor(col);
            g.fillRect(px, py, pw, ph);
        }
        // the labels (as the book's plan): LOC, G/S, PAPI, RVR, WX, the markers (the far ones as arrows at the left edge)
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 11));
        g.setColor(Color.WHITE);
        java.util.Set<String> done = new java.util.HashSet<String>();
        for (BookSchematics.Cell cl : s.cells) {
            String t = label(cl);
            if (cl.box == null || t == null || !done.add(t + (cl.planKind == PlannerMath.RVR ? String.valueOf(cl.box[0]) : ""))) {
                continue;
            }
            double al = (cl.box[0] + cl.box[1]) / 2, ac = (cl.box[2] + cl.box[3]) / 2;
            if (al < a0) {
                t = "< " + t + " " + Math.round(-al);
                al = a0 + 1;
            }
            int tx = (int) ((al - a0) * kx), ty = (int) (cy + ac * kz) + (ac >= 0 ? 14 : -5);
            int tw = g.getFontMetrics().stringWidth(t);
            g.drawString(t, Math.max(2, Math.min(W - tw - 2, tx - (al <= a0 + 2 ? 0 : tw / 2))), Math.max(12, Math.min(H - 3, ty)));
        }
        g.dispose();
        return img;
    }

    /** The plan's label of a planned thing (null: none). */
    static String label(BookSchematics.Cell c) {
        switch (c.planKind) {
            case PlannerMath.LOC:
                return "LOC";
            case PlannerMath.GS:
                return "G/S";
            case PlannerMath.MARKER: {
                double a = -(c.box[0] + c.box[1]) / 2;
                for (int i = 0; i < com.sva.airport.MarkerMath.NOMINAL.length; i++) {
                    if (Math.abs(com.sva.airport.MarkerMath.NOMINAL[i] - a) < 1) {
                        return BookSchematics.markerName(i);
                    }
                }
                return "M";
            }
            case PlannerMath.RVR:
                return "RVR";
            case PlannerMath.WEATHER:
                return "WX";
            case PlannerMath.PAPI:
                return "PAPI";
            case PlannerMath.ALS:
                return "ALS";
            case PlannerMath.LOC_SHELTER:
            case PlannerMath.GS_SHELTER:
                return "SH";
            default:
                return null;
        }
    }
}
