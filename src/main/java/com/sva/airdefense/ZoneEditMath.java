package com.sva.airdefense;

import java.util.ArrayList;
import java.util.List;

/**
 * Wave A (plan-next §9 A1, mock-up veh_ad_impl_a_1_kp_map): editing a zone on the command post's map with the mouse - pure,
 * the self-test checks it. The screen keeps the numeric fields; this is what the handles do:
 * <ul>
 * <li>a rectangle has its four CORNERS (the opposite one stays put), four EDGES (only that side moves), the CENTRE (the whole
 * zone moves) and the ROTATE handle (a knob ROT_KNOB blocks beyond the middle of its north side, before the turn - dragged,
 * the zone turns to face the mouse: rot = the compass bearing from its middle);</li>
 * <li>a circle has its CENTRE and the RADIUS knob (east of the centre);</li>
 * <li>«Привязка» (snap): positions to a SNAP-block grid, the turn to SNAP_DEG; off - to whole blocks and whole degrees
 * (the zone keeps ints anyway);</li>
 * <li>the edits of a drag are made from the zone as it was when the button went down (drag(orig, ...)) - no creeping
 * error; every finished drag / field change goes on the History (UNDO_MAX deep) for «Отменить»;</li>
 * <li>«Копировать» / «Вставить»: the clipboard's zone put down with its middle at the map's centre, «-2» after its name.</li>
 * </ul>
 * The result goes to the server as before (the «zone» command) and is sanitised there by Zone.read (sizes, band, codes,
 * the turn) - check() says here, before sending, what would be cut.
 */
public final class ZoneEditMath {

    /** Handles: none, the centre, the corners NW / NE / SE / SW, the edges N / E / S / W, the turn knob, the radius knob. */
    public static final int H_NONE = -1, H_CENTRE = 0, H_CORNER = 1, H_EDGE = 5, H_ROTATE = 9, H_RADIUS = 10;
    /** The turn knob's distance beyond the north side (blocks, before the turn). */
    public static final int ROT_KNOB = 12;
    /** Snap: positions to this many blocks, the turn to this many degrees. */
    public static final int SNAP = 8, SNAP_DEG = 15;
    /** The smallest rectangle side / circle radius (blocks). */
    public static final int MIN_SIDE = 2, MIN_R = 2;
    public static final int UNDO_MAX = 20;
    /** check() results. */
    public static final int OK = 0, E_TOO_BIG = 1, E_TOO_SMALL = 2, E_BAND = 3, E_NAME = 4, E_CODES = 5,
            /** Audit 2026-10-22 (bug 1): reaches further from the command post than the server's airdefense.zoneMaxDistance. */
            E_TOO_FAR = 6;
    public static final String[] CHECK_KEYS = {"ok", "tooBig", "tooSmall", "band", "name", "codes", "tooFar"};

    private ZoneEditMath() {
    }

    /** The handles of a zone: {x, z, handle} in the world. */
    public static List<double[]> handles(ZoneMath.Zone z) {
        List<double[]> out = new ArrayList<double[]>();
        double[] m = ZoneMath.middle(z);
        out.add(new double[] {m[0], m[1], H_CENTRE});
        if (z.shape == ZoneMath.CIRCLE) {
            out.add(new double[] {m[0] + z.r, m[1], H_RADIUS});
            return out;
        }
        double[][] c = ZoneMath.corners(z);
        for (int i = 0; i < 4; i++) {
            out.add(new double[] {c[i][0], c[i][1], H_CORNER + i});
        }
        for (int i = 0; i < 4; i++) {
            double[] a = c[i], b = c[(i + 1) % 4];
            out.add(new double[] {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, H_EDGE + i});
        }
        double[] k = ZoneMath.world(z, m[0], z.z0 - ROT_KNOB);
        out.add(new double[] {k[0], k[1], H_ROTATE});
        return out;
    }

    /** The handle within `tol` blocks of (x, z) - the nearest; the centre also anywhere inside the zone; else H_NONE. */
    public static int pick(ZoneMath.Zone z, double x, double zz, double tol) {
        int best = H_NONE;
        double bd = tol * tol;
        for (double[] h : handles(z)) {
            double d = (h[0] - x) * (h[0] - x) + (h[1] - zz) * (h[1] - zz);
            if (d <= bd && (best == H_NONE || d < bd)) {
                bd = d;
                best = (int) h[2];
            }
        }
        if (best == H_NONE && ZoneMath.contains2D(z, x, zz)) {
            best = H_CENTRE;
        }
        return best;
    }

    /** A value on the grid: SNAP blocks with snap on, whole blocks without. */
    public static int snap(double v, boolean on) {
        return on ? (int) Math.round(v / SNAP) * SNAP : (int) Math.round(v);
    }

    /** An angle on the grid (0..359): SNAP_DEG with snap on, whole degrees without. */
    public static int snapDeg(double deg, boolean on) {
        double d = ((deg % 360) + 360) % 360;
        int v = on ? (int) Math.round(d / SNAP_DEG) * SNAP_DEG : (int) Math.round(d);
        return v % 360;
    }

    /**
     * A drag: the zone as it was when the button went down (orig), the handle, the mouse's world point then (sx, sz) and
     * now (x, z). @return the edited copy (orig untouched)
     */
    public static ZoneMath.Zone drag(ZoneMath.Zone orig, int handle, double sx, double sz, double x, double zz, boolean snap) {
        ZoneMath.Zone z = orig.copy();
        if (handle == H_CENTRE) {
            double[] m = ZoneMath.middle(orig);
            double nx = m[0] + (x - sx), nz = m[1] + (zz - sz);
            if (z.shape == ZoneMath.CIRCLE) {
                z.cx = snap(nx - 0.5, snap);
                z.cz = snap(nz - 0.5, snap);
            } else {
                int w = orig.x1 - orig.x0, d = orig.z1 - orig.z0;
                z.x0 = snap(nx - (w + 1) / 2.0, snap);
                z.z0 = snap(nz - (d + 1) / 2.0, snap);
                z.x1 = z.x0 + w;
                z.z1 = z.z0 + d;
            }
            return z;
        }
        if (handle == H_RADIUS && z.shape == ZoneMath.CIRCLE) {
            double r = Math.hypot(x - (orig.cx + 0.5), zz - (orig.cz + 0.5));
            z.r = Math.max(MIN_R, Math.min(ZoneMath.MAX_HALF, snap(r, snap)));
            return z;
        }
        if (z.shape != ZoneMath.RECT) {
            return z;
        }
        if (handle == H_ROTATE) {
            double[] m = ZoneMath.middle(orig);
            z.rot = snapDeg(RadarMath.compass(x - m[0], zz - m[1]), snap);
            return z;
        }
        // corners and edges: in the zone's own frame (before its turn), the box's sides moved there, then the middle
        // put back so the side that did not move stays where it was in the world
        double[] p = ZoneMath.local(orig, x, zz);
        double l = orig.x0, t = orig.z0, r = orig.x1 + 1, b = orig.z1 + 1;
        boolean moveL = false, moveR = false, moveT = false, moveB = false;
        if (handle >= H_CORNER && handle < H_CORNER + 4) {
            int c = handle - H_CORNER;                              // 0 NW, 1 NE, 2 SE, 3 SW
            moveL = c == 0 || c == 3;
            moveR = c == 1 || c == 2;
            moveT = c == 0 || c == 1;
            moveB = c == 2 || c == 3;
        } else if (handle >= H_EDGE && handle < H_EDGE + 4) {
            int e = handle - H_EDGE;                                // 0 N, 1 E, 2 S, 3 W
            moveT = e == 0;
            moveR = e == 1;
            moveB = e == 2;
            moveL = e == 3;
        } else {
            return z;
        }
        double max = 2 * ZoneMath.MAX_HALF + 1;
        if (moveL) {
            l = Math.max(r - max, Math.min(r - MIN_SIDE, p[0]));
        }
        if (moveR) {
            r = Math.min(l + max, Math.max(l + MIN_SIDE, p[0]));
        }
        if (moveT) {
            t = Math.max(b - max, Math.min(b - MIN_SIDE, p[1]));
        }
        if (moveB) {
            b = Math.min(t + max, Math.max(t + MIN_SIDE, p[1]));
        }
        int w = Math.max(MIN_SIDE, (int) Math.round(r - l)), d = Math.max(MIN_SIDE, (int) Math.round(b - t));
        if (snap) {
            w = Math.max(SNAP, snap(w, true));
            d = Math.max(SNAP, snap(d, true));
            // the moving side snaps, the fixed one stays
            if (moveL) {
                l = r - w;
            } else {
                r = l + w;
            }
            if (moveT) {
                t = b - d;
            } else {
                b = t + d;
            }
        }
        // the new middle in the zone's own frame, then in the world (the turn kept)
        double[] nm = ZoneMath.world(orig, (l + r) / 2, (t + b) / 2);
        z.x0 = (int) Math.round(nm[0] - w / 2.0);
        z.z0 = (int) Math.round(nm[1] - d / 2.0);
        z.x1 = z.x0 + w - 1;
        z.z1 = z.z0 + d - 1;
        return z;
    }

    /** What the server's sanitising would change (the first problem), OK when nothing. */
    public static int check(ZoneMath.Zone z) {
        if (z.name == null || z.name.trim().length() == 0 || z.name.length() > 12) {
            return E_NAME;
        }
        if (z.shape == ZoneMath.RECT) {
            // bug 1: the sizes in long, as the server's Zone.read now (an int overflowed for a box of +-2e9)
            long w = (long) z.x1 - z.x0, d = (long) z.z1 - z.z0;
            if (w > 2L * ZoneMath.MAX_HALF || d > 2L * ZoneMath.MAX_HALF) {
                return E_TOO_BIG;
            }
            if (w + 1 < MIN_SIDE || d + 1 < MIN_SIDE) {
                return E_TOO_SMALL;
            }
        } else {
            if (z.r > ZoneMath.MAX_HALF) {
                return E_TOO_BIG;
            }
            if (z.r < 1) {
                return E_TOO_SMALL;
            }
        }
        if (z.lo < 0 || z.hi > ZoneMath.ALT_MAX || z.lo > z.hi) {
            return E_BAND;
        }
        if (!XpdrMath.valid(z.codeLo) || !XpdrMath.valid(z.codeHi) || z.codeLo > z.codeHi) {
            return E_CODES;
        }
        return OK;
    }

    /**
     * Bug 1: check() and then the reach - the whole zone within `maxDist` blocks of its command post at (kx, kz) (the
     * server's airdefense.zoneMaxDistance, sent with the post's picture; 0 - no limit / not known).
     */
    public static int check(ZoneMath.Zone z, int kx, int kz, int maxDist) {
        int c = check(z);
        return c != OK ? c : ZoneMath.withinReach(z, kx, kz, maxDist) ? OK : E_TOO_FAR;
    }

    /** «Вставить»: the clipboard's zone with its middle at (x, z) (snapped), «-2» after its name (12 characters at most). */
    public static ZoneMath.Zone pasteAt(ZoneMath.Zone clip, double x, double zz, boolean snap) {
        ZoneMath.Zone z = clip.copy();
        double[] m = ZoneMath.middle(clip);
        z = drag(z, H_CENTRE, m[0], m[1], x, zz, snap);
        String n = clip.name == null ? "" : clip.name;
        z.name = (n.length() > 10 ? n.substring(0, 10) : n) + "-2";
        return z;
    }

    /**
     * The undo stack of one editor: the zone before each finished edit, with the list index of the zone that was being
     * edited then (-1: a new one). Audit 2026-10-22 (bug 13): the index travels with the entry - an undo puts the editor
     * back on THAT zone, so «Сохранить» after it writes where the old copy came from; choosing another zone in the list
     * clears the stack (select). It used to keep the stack over a selection: edit zone A, choose B, «Отменить» put A's
     * old copy in the fields, «Сохранить» wrote it over B for good.
     */
    public static final class History {
        private final List<Entry> stack = new ArrayList<Entry>();
        private int zone = -1;

        /** One step back: the zone's list index (-1 a new zone) and the zone as it was. */
        public static final class Entry {
            public final int zone;
            public final ZoneMath.Zone before;

            Entry(int zone, ZoneMath.Zone before) {
                this.zone = zone;
                this.before = before;
            }
        }

        /** Before an edit of the zone being edited now (select()'s): the zone as it is (a copy). */
        public void push(ZoneMath.Zone before) {
            push(zone, before);
        }

        /** Before an edit of list zone `index` (-1 a new zone): the zone as it is (a copy). */
        public void push(int index, ZoneMath.Zone before) {
            if (before == null) {
                return;
            }
            stack.add(new Entry(index, before.copy()));
            while (stack.size() > UNDO_MAX) {
                stack.remove(0);
            }
        }

        /** The zone before the last edit (taken off the stack), null when there is none. */
        public ZoneMath.Zone undo() {
            Entry e = undoEntry();
            return e == null ? null : e.before;
        }

        /** The last edit's entry (taken off the stack): the editor goes back to its zone (index) and its copy; null none. */
        public Entry undoEntry() {
            if (stack.isEmpty()) {
                return null;
            }
            Entry e = stack.remove(stack.size() - 1);
            zone = e.zone;
            return e;
        }

        /** The editor now edits list zone `index` (-1 a new one): another zone than before - the stack is cleared. */
        public void select(int index) {
            if (index != zone) {
                stack.clear();
            }
            zone = index;
        }

        /**
         * The editor's zone became another one by an edit, not a choice (a paste makes a new zone of it): the stack is kept,
         * its entries keep the index they were made with.
         */
        public void moveTo(int index) {
            zone = index;
        }

        /** A new zone was saved as list zone `index`: the entries made while it was new (-1) now belong to it. */
        public void saved(int index) {
            for (int i = 0; i < stack.size(); i++) {
                Entry e = stack.get(i);
                if (e.zone < 0) {
                    stack.set(i, new Entry(index, e.before));
                }
            }
            if (zone < 0) {
                zone = index;
            }
        }

        /** The zone the editor edits (as select / undoEntry left it). */
        public int zone() {
            return zone;
        }

        public int size() {
            return stack.size();
        }

        public void clear() {
            stack.clear();
        }
    }
}
