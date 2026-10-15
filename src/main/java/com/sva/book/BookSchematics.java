package com.sva.book;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import com.sva.airdefense.AdShapeMath;
import com.sva.airdefense.RadarMath;
import com.sva.airport.MarkerMath;
import com.sva.airport.PlannerMath;

/**
 * Wave C, item 43: the handbook's build schematics - each structure's cells taken from the REAL multiblock definitions
 * (nothing drawn by hand): the air-defence shapes (AdShapeMath.worldCells - the same call BlockAirDefenseSVA.build uses),
 * the control tower (TileEntityTowerSVA.PARTS straight up), the ILS antennas (the parts of TileEntityIlsAntennaSVA.parts),
 * the air-defence complex (the six structures laid out round the command post) and the ILS runway (PlannerMath.plan - the
 * planner tablet's own layout, with its critical areas). The book draws them layer by layer (GuiBookSVA, «Слой N из M»)
 * with the list of what to place and how many; the hologram (client.BookHoloSVA) puts the same cells in the world turned
 * to a facing; the world test builds each structure with the placement code and compares.
 * <p>
 * Facing (AdShapeMath's): 0 south, 1 west, 2 north, 3 east - the way the front looks. A structure placed by a player looks
 * back at him, so the player places it looking the other way ((facing + 2) &amp; 3) - lookFor().
 */
public final class BookSchematics {

    /** Cell roles: the block the player places, a part the placement adds by itself, a concrete pad part, a planned thing. */
    public static final int MASTER = 0, PART = 1, PAD = 2, PLAN = 3;
    /** The schematics. */
    public static final String[] IDS = {"ad_survey", "ad_track", "ad_height", "ad_gun", "ad_missile", "ad_kp", "ad_complex", "tower", "ils_loc",
        "ils_gs", "airport"};
    /** The ILS runway drawn in the book / the hologram: its length and width (blocks), the glide angle. */
    public static final int RWY_LEN = 160, RWY_WIDTH = 5;
    public static final double RWY_ANGLE = 3;
    /** The ILS antenna kinds (TileEntityIlsAntennaSVA.LOC / GS) and the tower's parts (TileEntityTowerSVA.PARTS). */
    public static final int LOC = 0, GS = 1;
    /** The composite: each structure's master offset {x, z} from the command post's master at facing 0, and its shape. */
    static final int[][] COMPLEX = {{0, 0, AdShapeMath.KP}, {-8, -7, AdShapeMath.SURVEY}, {3, -7, AdShapeMath.TRACK}, {8, -7, AdShapeMath.HEIGHT},
        {-7, 6, AdShapeMath.GUN}, {7, 7, AdShapeMath.MISSILE}};

    private BookSchematics() {
    }

    /** One cell: offset from the anchor, role, textures (block texture names: top / side), the block expected there. */
    public static final class Cell {
        public final int x, y, z, role;
        public final String top, side, block;
        public final int meta;
        /** A planned thing's box in the runway frame (PLAN cells; else null): {along0, along1, across0, across1}, its kind. */
        public double[] box;
        public int planKind = -1;
        /** The structure (of a composite) this cell belongs to; -1 for single ones. */
        public int sub = -1;

        Cell(int x, int y, int z, int role, String top, String side, String block, int meta) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.role = role;
            this.top = top;
            this.side = side;
            this.block = block;
            this.meta = meta;
        }
    }

    /** A line of the block list: an item ref ("mod:name:meta"), how many, placed by hand or added by the placement. */
    public static final class Entry {
        public final String item;
        public final int count;
        public final boolean auto;
        /** A lang key for entries that are not items ("" for items). */
        public final String key;

        Entry(String item, int count, boolean auto, String key) {
            this.item = item;
            this.count = count;
            this.auto = auto;
            this.key = key;
        }
    }

    /** A structure of a composite: its master's offset, facing, shape. */
    public static final class Sub {
        public final int x, z, facing, shape;

        Sub(int x, int z, int facing, int shape) {
            this.x = x;
            this.z = z;
            this.facing = facing;
            this.shape = shape;
        }
    }

    /** One schematic turned to a facing. */
    public static final class Schem {
        public final String id;
        public final int facing;
        /** A plan (the runway: drawn from above), not a block structure. */
        public boolean plan;
        public final List<Cell> cells = new ArrayList<Cell>();
        public final List<Entry> list = new ArrayList<Entry>();
        public final List<Sub> subs = new ArrayList<Sub>();

        Schem(String id, int facing) {
            this.id = id;
            this.facing = facing & 3;
        }

        /** The layers (distinct y, low to high). */
        public int[] layers() {
            TreeSet<Integer> ys = new TreeSet<Integer>();
            for (Cell c : cells) {
                ys.add(Integer.valueOf(c.y));
            }
            int[] a = new int[ys.size()];
            int i = 0;
            for (Integer y : ys) {
                a[i++] = y.intValue();
            }
            return a;
        }

        /** The cells of a layer. */
        public List<Cell> layer(int y) {
            List<Cell> l = new ArrayList<Cell>();
            for (Cell c : cells) {
                if (c.y == y) {
                    l.add(c);
                }
            }
            return l;
        }

        /** {minX, minY, minZ, maxX, maxY, maxZ} of the cells. */
        public int[] bounds() {
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (Cell c : cells) {
                b[0] = Math.min(b[0], c.x);
                b[1] = Math.min(b[1], c.y);
                b[2] = Math.min(b[2], c.z);
                b[3] = Math.max(b[3], c.x);
                b[4] = Math.max(b[4], c.y);
                b[5] = Math.max(b[5], c.z);
            }
            return b;
        }

        public int count(int role) {
            int n = 0;
            for (Cell c : cells) {
                n += c.role == role ? 1 : 0;
            }
            return n;
        }
    }

    public static boolean known(String id) {
        for (String s : IDS) {
            if (s.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** The way a player looks to place a structure that faces `facing` (the front looks back at him). */
    public static int lookFor(String id, int facing) {
        return id.startsWith("ils_") || id.equals("airport") ? facing & 3 : (facing + 2) & 3;
    }

    /** The shape of an air-defence schematic id (-1: not one). */
    public static int shapeOf(String id) {
        for (int s = 0; s < AdShapeMath.SHAPES; s++) {
            if (id.equals("ad_" + SHAPE_KEYS[s])) {
                return s;
            }
        }
        return -1;
    }

    static final String[] SHAPE_KEYS = {"survey", "track", "height", "gun", "missile", "kp"};

    /** The block / item of an air-defence shape: registry name and meta (the command post's meta: its screen side). */
    public static String adBlock(int shape) {
        return shape <= AdShapeMath.HEIGHT ? "siliconagevehicles:radar" : shape <= AdShapeMath.MISSILE ? "siliconagevehicles:launcher"
                : "siliconagevehicles:commandPost";
    }

    public static int adMeta(int shape, int facing) {
        switch (shape) {
            case AdShapeMath.SURVEY:
                return RadarMath.SURVEY;
            case AdShapeMath.TRACK:
                return RadarMath.TRACK;
            case AdShapeMath.HEIGHT:
                return RadarMath.HEIGHT;
            case AdShapeMath.GUN:
                return 0;
            case AdShapeMath.MISSILE:
                return 1;
            default:
                return AdShapeMath.sideFromFacing(facing);
        }
    }

    /** The item meta of a shape (the command post's item is meta 0). */
    public static int adItemMeta(int shape) {
        return shape == AdShapeMath.KP ? 0 : adMeta(shape, 0);
    }

    static String adTex(int shape) {
        return shape <= AdShapeMath.HEIGHT ? "radar_" + RadarMath.KEYS[shape] : shape <= AdShapeMath.MISSILE ? "launcher_" + (shape == AdShapeMath.GUN ? "gun" : "missile")
                : "kp";
    }

    /** A schematic turned to `facing`; null for an unknown id. */
    public static Schem get(String id, int facing) {
        int shape = shapeOf(id);
        if (shape >= 0) {
            Schem s = new Schem(id, facing);
            adCells(s, shape, 0, 0, facing, -1);
            s.list.add(new Entry(adBlock(shape) + ":" + adItemMeta(shape), 1, false, ""));
            int parts = AdShapeMath.cells(shape).length - 1;
            if (parts > 0) {
                s.list.add(new Entry("siliconagevehicles:adPart:0", parts, true, "sva.book.schem.parts"));
            }
            return s;
        }
        if ("ad_complex".equals(id)) {
            Schem s = new Schem(id, facing);
            for (int i = 0; i < COMPLEX.length; i++) {
                int[] k = COMPLEX[i];
                int[] w = rot(k[0], k[1], facing);
                s.subs.add(new Sub(w[0], w[1], facing, k[2]));
                adCells(s, k[2], w[0], w[1], facing, i);
            }
            for (int[] k : COMPLEX) {
                s.list.add(new Entry(adBlock(k[2]) + ":" + adItemMeta(k[2]), 1, false, ""));
            }
            s.list.add(new Entry("siliconagevehicles:adPart:0", s.count(PART) + s.count(PAD), true, "sva.book.schem.parts"));
            return s;
        }
        if ("tower".equals(id)) {
            Schem s = new Schem(id, facing);
            s.cells.add(new Cell(0, 0, 0, MASTER, "ils_side", "ils_side", "siliconagevehicles:tower", 0));
            for (int dy = 1; dy <= TOWER_PARTS; dy++) {
                s.cells.add(new Cell(0, dy, 0, PART, "ils_side", "ils_side", "siliconagevehicles:aptPart", 0));
            }
            s.list.add(new Entry("siliconagevehicles:tower:0", 1, false, ""));
            s.list.add(new Entry("siliconagevehicles:aptPart:0", TOWER_PARTS, true, "sva.book.schem.parts"));
            return s;
        }
        if ("ils_loc".equals(id) || "ils_gs".equals(id)) {
            int kind = "ils_loc".equals(id) ? LOC : GS;
            Schem s = new Schem(id, facing);
            s.cells.add(new Cell(0, 0, 0, MASTER, "ils_top", "ils_side", "siliconagevehicles:ilsAntenna", kind));
            for (int[] o : antennaParts(kind, facing)) {
                s.cells.add(new Cell(o[0], o[1], o[2], PART, "ils_top", "ils_side", "siliconagevehicles:aptPart", 0));
            }
            s.list.add(new Entry("siliconagevehicles:ilsAntenna:" + kind, 1, false, ""));
            s.list.add(new Entry("siliconagevehicles:aptPart:0", s.cells.size() - 1, true, "sva.book.schem.parts"));
            s.list.add(new Entry("siliconagevehicles:ilsShelter:0", 1, false, ""));
            return s;
        }
        if ("airport".equals(id)) {
            return airport(facing);
        }
        return null;
    }

    /** The tower's parts over its master (TileEntityTowerSVA.PARTS; the self-test compares). */
    public static final int TOWER_PARTS = 5;

    /**
     * The ILS antennas' parts (TileEntityIlsAntennaSVA.parts - copied here as pure data so the dedicated server's self-test
     * needs no tile class; the self-test compares the two): G/S two mast sections up; LOC four elements across the placer's
     * look (along X when he looks along Z).
     */
    public static int[][] antennaParts(int kind, int facing) {
        if (kind == GS) {
            return new int[][] {{0, 1, 0}, {0, 2, 0}};
        }
        boolean alongX = (facing & 1) == 0;
        int[] d = {-2, -1, 1, 2};
        int[][] o = new int[4][];
        for (int i = 0; i < 4; i++) {
            o[i] = alongX ? new int[] {d[i], 0, 0} : new int[] {0, 0, d[i]};
        }
        return o;
    }

    /** An air-defence structure's cells (AdShapeMath.worldCells) with its master at (ox, 0, oz). */
    static void adCells(Schem s, int shape, int ox, int oz, int facing, int sub) {
        int[][] c = AdShapeMath.worldCells(shape, facing);
        String tex = adTex(shape), block = adBlock(shape);
        for (int i = 0; i < c.length; i++) {
            Cell cell;
            if (i == 0) {
                cell = new Cell(ox, 0, oz, MASTER, tex + "_top", shape == AdShapeMath.KP ? "kp_front" : tex + "_side", block, adMeta(shape, facing));
            } else if (c[i][3] == AdShapeMath.PAD) {
                cell = new Cell(ox + c[i][0], c[i][1], oz + c[i][2], PAD, "adBase", "adBase", "siliconagevehicles:adPart", AdShapeMath.PAD);
            } else {
                cell = new Cell(ox + c[i][0], c[i][1], oz + c[i][2], PART, tex + "_top", tex + "_side", "siliconagevehicles:adPart", AdShapeMath.FULL);
            }
            cell.sub = sub;
            s.cells.add(cell);
        }
    }

    /** An offset given at facing 0 (south) turned to `facing` (AdShapeMath's frame: f = z, r = -x at facing 0). */
    public static int[] rot(int x, int z, int facing) {
        return AdShapeMath.toWorld(facing, z, -x);
    }

    /**
     * The ILS runway: the strip (runway blocks), its two ends, and every PlannerMath item of a RWY_LEN x RWY_WIDTH runway
     * landing over the anchor toward `facing` (the anchor is the landing threshold's marker on the centre line): along =
     * facing's forward, across + = its right.
     */
    static Schem airport(int facing) {
        Schem s = new Schem("airport", facing);
        s.plan = true;
        List<PlannerMath.Item> items = PlannerMath.plan(RWY_LEN, RWY_WIDTH, RWY_ANGLE);
        for (PlannerMath.Item it : items) {
            int[] w = runwayCell(it.along(), it.across(), facing);
            Cell c = new Cell(w[0], 0, w[1], PLAN, "", "", planBlock(it.kind), planMeta(it.kind));
            c.box = new double[] {it.a0, it.a1, it.c0, it.c1};
            c.planKind = it.kind;
            s.cells.add(c);
        }
        int markers = 0, rvr = 0, papi = 0;
        for (PlannerMath.Item it : items) {
            markers += it.kind == PlannerMath.MARKER ? 1 : 0;
            rvr += it.kind == PlannerMath.RVR ? 1 : 0;
            papi += it.kind == PlannerMath.PAPI ? 1 : 0;
        }
        s.list.add(new Entry("siliconagevehicles:runway:0", RWY_LEN * RWY_WIDTH, false, ""));
        s.list.add(new Entry("siliconagevehicles:runwayEnd:0", 2, false, ""));
        s.list.add(new Entry("siliconagevehicles:ilsAntenna:" + LOC, 1, false, ""));
        s.list.add(new Entry("siliconagevehicles:ilsAntenna:" + GS, 1, false, ""));
        s.list.add(new Entry("siliconagevehicles:ilsShelter:0", 2, false, ""));
        s.list.add(new Entry("siliconagevehicles:aptDevice:" + K_MARKER, markers, false, ""));
        s.list.add(new Entry("siliconagevehicles:aptDevice:" + K_RVR, rvr, false, ""));
        s.list.add(new Entry("siliconagevehicles:papi:0", papi, false, ""));
        s.list.add(new Entry("siliconagevehicles:aptDevice:" + K_WX, 1, false, ""));
        s.list.add(new Entry("siliconagevehicles:aptDevice:" + K_SOCK, 1, false, ""));
        s.list.add(new Entry("siliconagevehicles:tower:0", 1, false, ""));
        s.list.add(new Entry("siliconagevehicles:aptPlanner:0", 1, false, "sva.book.schem.tablet"));
        return s;
    }

    /** AirportDataSVA's device kinds (the aptDevice metas). */
    public static final int K_MARKER = 0, K_RVR = 1, K_WX = 2, K_SOCK = 3;

    static String planBlock(int kind) {
        switch (kind) {
            case PlannerMath.LOC:
            case PlannerMath.GS:
                return "siliconagevehicles:ilsAntenna";
            case PlannerMath.LOC_SHELTER:
            case PlannerMath.GS_SHELTER:
                return "siliconagevehicles:ilsShelter";
            case PlannerMath.MARKER:
            case PlannerMath.RVR:
            case PlannerMath.WEATHER:
            case PlannerMath.WINDSOCK:
                return "siliconagevehicles:aptDevice";
            case PlannerMath.PAPI:
                return "siliconagevehicles:papi";
            default:
                return "";
        }
    }

    static int planMeta(int kind) {
        switch (kind) {
            case PlannerMath.GS:
                return GS;
            case PlannerMath.RVR:
                return K_RVR;
            case PlannerMath.WEATHER:
                return K_WX;
            case PlannerMath.WINDSOCK:
                return K_SOCK;
            default:
                return 0;
        }
    }

    /** The runway frame (along from the threshold toward `facing`, across + right) -> a world offset {dx, dz}, rounded. */
    public static int[] runwayCell(double along, double across, int facing) {
        double[] w = runwayWorld(along, across, facing);
        return new int[] {(int) Math.floor(w[0]), (int) Math.floor(w[1])};
    }

    /** ... unrounded {dx, dz} (blocks from the anchor block's centre). */
    public static double[] runwayWorld(double along, double across, int facing) {
        int fx = AdShapeMath.fx(facing), fz = AdShapeMath.fz(facing);
        // right of a look along (fx, fz) is (-fz, fx)
        return new double[] {along * fx - across * fz + 0.5, along * fz + across * fx + 0.5};
    }

    /** The markers' names (labels), from MarkerMath. */
    public static String markerName(int i) {
        return MarkerMath.NAMES[Math.max(0, Math.min(MarkerMath.NAMES.length - 1, i))];
    }
}
