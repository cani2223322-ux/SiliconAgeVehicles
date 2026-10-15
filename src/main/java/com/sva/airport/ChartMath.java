package com.sva.airport;

import java.util.ArrayList;
import java.util.List;

import com.sva.vehicle.LinerIls;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Airport wave B2 (plan-next §9, item 34): the «Карта подхода» - one approach printed by the tower on paper. Pure data and
 * layout (the item's NBT, the screen and the preview draw the same primitives - layout()):
 * <ul>
 * <li><b>The data</b> (Chart): the airport (ICAO, name, elevation), the runway (designator, course, length), the ILS (ident,
 * frequency, category, G/S angle), the fixes (IF, FAF, the threshold, the missed approach with its altitudes) and the
 * transitions, the minimums (DH by the category, the RVR it needs), the lights (runway lights, ALS, PAPI), the chart's
 * VERSION - the tower's chart version when printed;</li>
 * <li><b>Outdated</b>: the tower bumps its version whenever its airport changes (its code, name, runways, ILS, the
 * approaches' geometry); a chart whose version is not the tower's now - or whose airport is gone - shows «УСТАРЕЛА»;</li>
 * <li><b>The layout</b> on a W x H sheet: the header, the plan view (north up: the runway, the final course, IF / FAF, the
 * missed approach dashed, the transitions from the entry fixes), the profile (the level segment to the FAF, the glide path
 * to the threshold, the altitudes, the distances in km-eq; the missed approach's climb), the frequency / course / minimums
 * box, the lights and airport notes.</li>
 * </ul>
 */
public final class ChartMath {

    /** The sheet (layout units). */
    public static final float W = 400, H = 300;
    /** Primitive kinds. */
    public static final int LINE = 0, DASH = 1, TEXT = 2, RECT = 3, FILL = 4, CIRCLE = 5, TRI = 6;
    /** Colours (RGB): ink, the approach's magenta, the missed approach's blue, the paper's grey, the warning's red. */
    public static final int INK = 0x1A1A1A, MAG = 0xA02090, BLUE = 0x2A5BB8, GREY = 0x8A8A86, RED = 0xC02020, PAPER = 0xF4F1E6, RWY = 0x303030;

    private ChartMath() {
    }

    /** One fix on the chart: ident, local position (blocks: along the final + toward the runway, across + right), altitude. */
    public static final class CFix {
        public String id = "";
        public double x, z;
        public int alt, kind;
    }

    public static final class Chart {
        public String icao = "", name = "", rwy = "", ils = "", appr = "";
        public int elev, course, len, freq, cat, version, width = 5;
        public double angle = 3, tx, tz;
        /** Lights: the runway's (mode at print), ALS at this end, PAPI near the threshold. */
        public boolean als, papi, rwyLights = true;
        public final List<CFix> fixes = new ArrayList<CFix>(), missed = new ArrayList<CFix>(), trans = new ArrayList<CFix>();

        public int dh() {
            return (int) Math.round(IlsCat.minHeight(cat));
        }

        /** The RVR the category needs (m-eq). */
        public int rvrMin() {
            return cat >= 3 ? 75 : cat == 2 ? (int) RvrMath.CAT2_RVR : (int) RvrMath.CAT1_RVR;
        }

        public CFix fix(int kind) {
            for (CFix f : fixes) {
                if (f.kind == kind) {
                    return f;
                }
            }
            for (CFix f : missed) {
                if (f.kind == kind) {
                    return f;
                }
            }
            return null;
        }
    }

    static CFix cfix(ApproachMath.Fix f) {
        CFix c = new CFix();
        c.id = f.id;
        c.x = f.x;
        c.z = f.z;
        c.alt = f.alt;
        c.kind = f.kind;
        return c;
    }

    /** The chart of an approach (the tower's print). */
    public static Chart of(String icao, String name, ApproachMath.Approach a, int len, int width, boolean als, boolean papi, boolean rwyLights, int version) {
        Chart c = new Chart();
        c.icao = icao;
        c.name = name;
        c.appr = a.id;
        c.rwy = a.rwy;
        c.ils = a.ils;
        c.freq = a.freq;
        c.cat = a.cat;
        c.angle = a.angle;
        c.course = (int) Math.round(a.course) % 360;
        c.elev = a.elev;
        c.tx = a.tx;
        c.tz = a.tz;
        c.len = len;
        c.width = width;
        c.als = als;
        c.papi = papi;
        c.rwyLights = rwyLights;
        c.version = version;
        for (ApproachMath.Fix f : a.fixes) {
            c.fixes.add(cfix(f));
        }
        for (ApproachMath.Fix f : a.missed) {
            c.missed.add(cfix(f));
        }
        for (ApproachMath.Fix f : a.trans) {
            c.trans.add(cfix(f));
        }
        return c;
    }

    /** Outdated: the tower's version now differs (towerVersion < 0: the airport is gone). */
    public static boolean outdated(int chartVersion, int towerVersion) {
        return towerVersion < 0 || chartVersion != towerVersion;
    }

    // ---------------------------------------------------------------- NBT (the item)

    static NBTTagList fixList(List<CFix> l) {
        NBTTagList n = new NBTTagList();
        for (CFix f : l) {
            NBTTagCompound t = new NBTTagCompound();
            t.setString("Id", f.id);
            t.setDouble("X", f.x);
            t.setDouble("Z", f.z);
            t.setInteger("A", f.alt);
            t.setInteger("K", f.kind);
            n.appendTag(t);
        }
        return n;
    }

    static void readFixes(NBTTagList n, List<CFix> out) {
        out.clear();
        for (int i = 0; i < n.tagCount() && i < 12; i++) {
            NBTTagCompound t = n.getCompoundTagAt(i);
            CFix f = new CFix();
            f.id = t.getString("Id");
            f.x = t.getDouble("X");
            f.z = t.getDouble("Z");
            f.alt = t.getInteger("A");
            f.kind = t.getInteger("K");
            out.add(f);
        }
    }

    public static void write(Chart c, NBTTagCompound t) {
        t.setString("Apt", c.icao);
        t.setString("Name", c.name);
        t.setString("Appr", c.appr);
        t.setString("Rwy", c.rwy);
        t.setString("Ils", c.ils);
        t.setInteger("Freq", c.freq);
        t.setInteger("Cat", c.cat);
        t.setDouble("Ang", c.angle);
        t.setInteger("Crs", c.course);
        t.setInteger("Elev", c.elev);
        t.setDouble("Tx", c.tx);
        t.setDouble("Tz", c.tz);
        t.setInteger("Len", c.len);
        t.setInteger("W", c.width);
        t.setByte("Lt", (byte) ((c.als ? 1 : 0) | (c.papi ? 2 : 0) | (c.rwyLights ? 4 : 0)));
        t.setInteger("Ver", c.version);
        t.setTag("Fix", fixList(c.fixes));
        t.setTag("Miss", fixList(c.missed));
        t.setTag("Trans", fixList(c.trans));
    }

    /** null when the tag holds no chart (a blank sheet). */
    public static Chart read(NBTTagCompound t) {
        if (t == null || !t.hasKey("Apt") || t.getString("Apt").length() == 0) {
            return null;
        }
        Chart c = new Chart();
        c.icao = t.getString("Apt");
        c.name = t.getString("Name");
        c.appr = t.getString("Appr");
        c.rwy = t.getString("Rwy");
        c.ils = t.getString("Ils");
        c.freq = t.getInteger("Freq");
        c.cat = t.getInteger("Cat");
        c.angle = t.getDouble("Ang");
        c.course = t.getInteger("Crs");
        c.elev = t.getInteger("Elev");
        c.tx = t.getDouble("Tx");
        c.tz = t.getDouble("Tz");
        c.len = t.getInteger("Len");
        c.width = t.hasKey("W") ? t.getInteger("W") : 5;
        int lt = t.getByte("Lt");
        c.als = (lt & 1) != 0;
        c.papi = (lt & 2) != 0;
        c.rwyLights = (lt & 4) != 0;
        c.version = t.getInteger("Ver");
        readFixes(t.getTagList("Fix", 10), c.fixes);
        readFixes(t.getTagList("Miss", 10), c.missed);
        readFixes(t.getTagList("Trans", 10), c.trans);
        return c;
    }

    // ---------------------------------------------------------------- the layout

    /** One thing to draw (layout units, origin top-left): a line / dashed line / text / rectangle / filled box / circle / triangle. */
    public static final class Prim {
        public int kind, colour, align;
        public float x0, y0, x1, y1, size = 7;
        public String text = "";
        /** A text's lang key (the screen translates it; arguments in `args`). */
        public String[] args;
        public boolean key;
    }

    static Prim p(List<Prim> l, int kind, float x0, float y0, float x1, float y1, int col) {
        Prim q = new Prim();
        q.kind = kind;
        q.x0 = x0;
        q.y0 = y0;
        q.x1 = x1;
        q.y1 = y1;
        q.colour = col;
        l.add(q);
        return q;
    }

    static Prim t(List<Prim> l, float x, float y, String s, int col, float size, int align) {
        Prim q = p(l, TEXT, x, y, x, y, col);
        q.text = s;
        q.size = size;
        q.align = align;
        return q;
    }

    /** A lang-key text (the screen / the preview translate it). */
    static Prim k(List<Prim> l, float x, float y, String key, int col, float size, int align, String... args) {
        Prim q = t(l, x, y, key, col, size, align);
        q.key = true;
        q.args = args;
        return q;
    }

    /** km-eq with one decimal and a comma. */
    public static String km(double blocks) {
        return AptScale.kmText(blocks, ',');
    }

    /** The whole sheet (see the class comment). `outdated`: the red stamp. */
    public static List<Prim> layout(Chart c, boolean outdated) {
        List<Prim> l = new ArrayList<Prim>();
        p(l, FILL, 0, 0, W, H, PAPER);
        p(l, RECT, 2, 2, W - 2, H - 2, INK);
        // header
        t(l, 8, 8, c.icao, INK, 12, 0);
        t(l, 52, 10, c.name, INK, 8, 0);
        k(l, W - 8, 8, "sva.chart.title", INK, 9, 1, "ILS " + c.rwy, IlsCat.catText(c.cat));
        k(l, W - 8, 20, "sva.chart.version", GREY, 6, 1, String.valueOf(c.version));
        p(l, LINE, 2, 30, W - 2, 30, INK);
        // the info box (frequency, course, minimums)
        float bx = 4, by = 32, bh = 34;
        float[] cols = {4, 84, 154, 214, 296, W - 4};
        for (int i = 1; i < cols.length - 1; i++) {
            p(l, LINE, cols[i], by, cols[i], by + bh, INK);
        }
        k(l, cols[0] + 3, by + 3, "sva.chart.loc", GREY, 5.5F, 0);
        t(l, cols[0] + 3, by + 13, c.ils + "  " + LinerIls.freqText(c.freq).replace('.', ','), MAG, 8, 0);
        k(l, cols[1] + 3, by + 3, "sva.chart.crs", GREY, 5.5F, 0);
        t(l, cols[1] + 3, by + 13, TowerMath.three(c.course), MAG, 9, 0);
        k(l, cols[2] + 3, by + 3, "sva.chart.gs", GREY, 5.5F, 0);
        t(l, cols[2] + 3, by + 13, String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(c.angle)).replace('.', ','), INK, 9, 0);
        CFix faf = c.fix(ApproachMath.F_FAF);
        k(l, cols[3] + 3, by + 3, "sva.chart.faf", GREY, 5.5F, 0);
        t(l, cols[3] + 3, by + 13, (faf == null ? "-" : faf.id + " " + faf.alt), INK, 8, 0);
        k(l, cols[4] + 3, by + 3, "sva.chart.mins", GREY, 5.5F, 0);
        if (c.cat >= 3) {
            k(l, cols[4] + 3, by + 13, "sva.chart.minsCat3", INK, 7, 0, String.valueOf(c.rvrMin()));
        } else {
            k(l, cols[4] + 3, by + 13, "sva.chart.minsDh", INK, 7, 0, String.valueOf(c.dh()), String.valueOf(c.rvrMin()));
        }
        k(l, cols[4] + 3, by + 23, "sva.chart.elev", GREY, 5.5F, 0, String.valueOf(c.elev));
        p(l, LINE, 2, by + bh, W - 2, by + bh, INK);
        // the plan view (left) and the profile (right bottom)
        plan(l, c, 4, by + bh + 2, 236, 196);
        profile(l, c, 242, by + bh + 2, W - 246, 120);
        notes(l, c, 242, by + bh + 126, W - 246, 70);
        // footer: the missed approach in words
        CFix ma = c.fix(ApproachMath.F_MA), ret = c.fix(ApproachMath.F_RETURN);
        p(l, LINE, 2, H - 28, W - 2, H - 28, INK);
        k(l, 6, H - 24, "sva.chart.missed", BLUE, 6.5F, 0, TowerMath.three(c.course), ma == null ? "-" : String.valueOf(ma.alt),
                ret == null ? "-" : ret.id, ret == null ? "-" : String.valueOf(ret.alt));
        k(l, 6, H - 13, "sva.chart.foot", GREY, 5.5F, 0, c.icao, String.valueOf(c.version));
        if (outdated) {
            Prim s = k(l, W / 2, H / 2 - 14, "sva.chart.outdated", RED, 22, 2);
            s.size = 22;
            p(l, RECT, W / 2 - 110, H / 2 - 20, W / 2 + 110, H / 2 + 16, RED);
        }
        return l;
    }

    /** The plan view (north up), the box (x, y, w, h). */
    static void plan(List<Prim> l, Chart c, float x, float y, float w, float h) {
        p(l, RECT, x, y, x + w, y + h, GREY);
        k(l, x + 3, y + 3, "sva.chart.plan", GREY, 5.5F, 0);
        // the world box of the approach itself (the transitions' entry fixes far out are arrows to the edge)
        double minX = c.tx, maxX = c.tx, minZ = c.tz, maxZ = c.tz;
        List<CFix> all = new ArrayList<CFix>();
        all.addAll(c.fixes);
        all.addAll(c.missed);
        for (CFix f : all) {
            minX = Math.min(minX, f.x);
            maxX = Math.max(maxX, f.x);
            minZ = Math.min(minZ, f.z);
            maxZ = Math.max(maxZ, f.z);
        }
        double cr = Math.toRadians(c.course);
        double ex = c.tx + Math.sin(cr) * c.len, ez = c.tz - Math.cos(cr) * c.len;
        minX = Math.min(minX, ex);
        maxX = Math.max(maxX, ex);
        minZ = Math.min(minZ, ez);
        maxZ = Math.max(maxZ, ez);
        double span = Math.max(maxX - minX, maxZ - minZ) * 1.12 + 1;
        final double s = Math.min(w - 16, h - 16) / span;
        final double ox = x + w / 2 - (minX + maxX) / 2 * s, oz = y + h / 2 + 4 - (minZ + maxZ) / 2 * s;
        // the north arrow and the scale bar (5 km-eq)
        p(l, TRI, x + w - 12, y + 6, x + w - 12, y + 16, INK);
        t(l, x + w - 12, y + 17, "N", INK, 5.5F, 2);
        float sb = (float) (500 * s);
        p(l, LINE, x + 6, y + h - 8, x + 6 + sb, y + h - 8, INK);
        t(l, x + 6 + sb / 2, y + h - 16, "5 KM", INK, 5, 2);
        // the transitions: dashed grey from the IF toward each entry fix, cut at the box's edge (the fix and its altitude there)
        CFix fi = c.fix(ApproachMath.F_IF);
        for (CFix f : c.trans) {
            if (fi == null) {
                break;
            }
            double ax = ox + fi.x * s, az = oz + fi.z * s, bx = ox + f.x * s, bz = oz + f.z * s;
            double k = 1;
            double[] lim = {x + 6, y + 14, x + w - 6, y + h - 16};
            if (bx < lim[0]) {
                k = Math.min(k, (lim[0] - ax) / (bx - ax));
            }
            if (bx > lim[2]) {
                k = Math.min(k, (lim[2] - ax) / (bx - ax));
            }
            if (bz < lim[1]) {
                k = Math.min(k, (lim[1] - az) / (bz - az));
            }
            if (bz > lim[3]) {
                k = Math.min(k, (lim[3] - az) / (bz - az));
            }
            float qx = (float) (ax + (bx - ax) * k), qz = (float) (az + (bz - az) * k);
            p(l, DASH, (float) ax, (float) az, qx, qz, GREY);
            p(l, TRI, qx - 3, qz - 2, qx + 3, qz + 2, INK);
            float lx = Math.max(x + 26, Math.min(x + w - 26, qx));
            float lz = Math.abs(qz - az) < 12 ? qz - 11 : qz + (qz > az ? -10 : 4);
            t(l, lx, lz, f.id + " +" + f.alt, INK, 5, 2);
        }
        // the final: IF - FAF - threshold, magenta
        float px = 0, pz = 0;
        boolean first = true;
        for (CFix f : c.fixes) {
            float fx = (float) (ox + f.x * s), fz = (float) (oz + f.z * s);
            if (!first) {
                p(l, LINE, px, pz, fx, fz, MAG);
            }
            first = false;
            px = fx;
            pz = fz;
            p(l, f.kind == ApproachMath.F_FAF ? TRI : CIRCLE, fx - 3, fz - 3, fx + 3, fz + 3, MAG);
            t(l, fx + 5, fz - 9, f.id, INK, 5.5F, 0);
            t(l, fx + 5, fz - 2, (f.kind == ApproachMath.F_FAF ? "" : "+") + f.alt, MAG, 5, 0);
        }
        float tx = (float) (ox + c.tx * s), tz = (float) (oz + c.tz * s);
        if (!first) {
            p(l, LINE, px, pz, tx, tz, MAG);
        }
        // the runway (a thick bar)
        float rx = (float) (ox + ex * s), rz = (float) (oz + ez * s);
        for (int i = -1; i <= 1; i++) {
            float dx = (float) (Math.cos(cr) * i * 0.8), dz = (float) (Math.sin(cr) * i * 0.8);
            p(l, LINE, tx + dx, tz + dz, rx + dx, rz + dz, RWY);
        }
        t(l, tx - 4, tz + 3, c.rwy, INK, 6, 1);
        // the missed approach: from the threshold, blue dashed
        float mx = tx, mz = tz;
        for (CFix f : c.missed) {
            float fx = (float) (ox + f.x * s), fz = (float) (oz + f.z * s);
            p(l, DASH, mx, mz, fx, fz, BLUE);
            mx = fx;
            mz = fz;
            if (f.kind == ApproachMath.F_MA) {
                p(l, CIRCLE, fx - 2.5F, fz - 2.5F, fx + 2.5F, fz + 2.5F, BLUE);
                t(l, fx + 4, fz + 2, f.id + " +" + f.alt, BLUE, 5, 0);
            }
        }
    }

    /** The profile: distance from the threshold (km-eq) left to right toward it, the altitudes. */
    static void profile(List<Prim> l, Chart c, float x, float y, float w, float h) {
        p(l, RECT, x, y, x + w, y + h, GREY);
        k(l, x + 3, y + 3, "sva.chart.profile", GREY, 5.5F, 0);
        CFix fi = c.fix(ApproachMath.F_IF), ff = c.fix(ApproachMath.F_FAF), ma = c.fix(ApproachMath.F_MA);
        if (fi == null || ff == null) {
            return;
        }
        double dIf = ApproachMath.IF_DIST, dFaf = ApproachMath.FAF_DIST;
        double top = Math.max(fi.alt, ma == null ? 0 : ma.alt) + 8, bot = c.elev - 2;
        float gx0 = x + 10, gx1 = x + w - 30, gy0 = y + 16, gy1 = y + h - 18;
        double sx = (gx1 - gx0) / dIf, sy = (gy1 - gy0) / (top - bot);
        // the ground and the runway
        p(l, LINE, gx0, gy1, x + w - 4, gy1, INK);
        float thx = gx1, thy = (float) (gy1 - (c.elev - bot) * sy);
        p(l, FILL, thx, thy - 1, x + w - 4, thy + 1, RWY);
        // level IF -> FAF, then the glide path to the threshold (TCH)
        float ix = (float) (gx1 - dIf * sx), fx = (float) (gx1 - dFaf * sx);
        float iy = (float) (gy1 - (fi.alt - bot) * sy), fy = (float) (gy1 - (ff.alt - bot) * sy);
        float ty = (float) (gy1 - (c.elev + IlsCat.TCH_NOMINAL - bot) * sy);
        p(l, LINE, ix, iy, fx, fy, MAG);
        p(l, LINE, fx, fy, thx, ty, MAG);
        p(l, TRI, fx - 3, fy - 7, fx + 3, fy - 7, MAG);
        t(l, ix, iy - 9, fi.id, INK, 5, 0);
        t(l, ix, iy + 3, "+" + fi.alt, MAG, 5, 0);
        t(l, fx, fy - 15, ff.id, INK, 5, 2);
        t(l, fx, fy + 3, String.valueOf(ff.alt), MAG, 5, 2);
        k(l, (fx + thx) / 2, (fy + ty) / 2 - 10, "sva.chart.gsAngle", MAG, 5, 2, String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(c.angle)).replace('.', ','));
        // distances (km-eq) under the axis
        t(l, ix, gy1 + 3, km(dIf), INK, 5, 2);
        t(l, fx, gy1 + 3, km(dFaf), INK, 5, 2);
        t(l, thx, gy1 + 3, "0", INK, 5, 2);
        // the missed approach climb (blue dashed up from the threshold)
        if (ma != null) {
            float my = (float) (gy1 - (ma.alt - bot) * sy);
            p(l, DASH, thx, ty, x + w - 6, my, BLUE);
            t(l, x + w - 6, my - 8, "+" + ma.alt, BLUE, 5, 1);
        }
        // the DH line
        if (c.cat < 3) {
            float dy = (float) (gy1 - (c.elev + c.dh() - bot) * sy);
            p(l, DASH, thx - 30, dy, thx + 2, dy, RED);
            k(l, thx - 32, dy - 3, "sva.chart.dh", RED, 4.5F, 1, String.valueOf(c.dh()));
        }
    }

    /** The lights and airport notes. */
    static void notes(List<Prim> l, Chart c, float x, float y, float w, float h) {
        p(l, RECT, x, y, x + w, y + h, GREY);
        k(l, x + 3, y + 3, "sva.chart.notes", GREY, 5.5F, 0);
        float ly = y + 13;
        k(l, x + 4, ly, "sva.chart.rwyInfo", INK, 6, 0, c.rwy, String.valueOf(AptScale.metres(c.len) > 0 ? (int) Math.round(AptScale.metres(c.len)) : 0),
                String.valueOf((int) Math.round(AptScale.metres(c.width))));
        ly += 10;
        k(l, x + 4, ly, c.als ? "sva.chart.als" : "sva.chart.noAls", INK, 6, 0);
        ly += 10;
        k(l, x + 4, ly, c.papi ? "sva.chart.papi" : "sva.chart.noPapi", INK, 6, 0);
        ly += 10;
        k(l, x + 4, ly, c.rwyLights ? "sva.chart.lights" : "sva.chart.lightsOff", INK, 6, 0);
        ly += 10;
        k(l, x + 4, ly, "sva.chart.scale", GREY, 5, 0);
    }
}
