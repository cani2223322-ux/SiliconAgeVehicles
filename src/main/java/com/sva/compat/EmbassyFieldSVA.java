package com.sva.compat;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

/**
 * «Лётное поле посольства» (docs/plan-embassy-airfield-addon.md §2.2): the add-on's own immutable copy of one embassy airfield - the
 * numbers of the core's AirfieldInfoSC (com.sc.embassy.api) taken once by EmbassyCompatSVA, so no other class of the add-on ever names
 * the core's API (an older core without it: no EmbassyFieldSVA is ever made). World blocks; boxes {minX, minZ, maxX, maxZ} inclusive,
 * points {x, y, z}; y of the paving and of the flush lights is floorY. Every array getter returns a copy. «low» - the end on the -a
 * side of the embassy's frame: a landing over it flies headingLow (numberLow painted there).
 * <p>
 * Written whole by the coordinator; the packages only read it. The questions of the protected zone's and the neutral sky's shape are
 * the core's (EmbassyCompatSVA.inZone / neutral with this field) - the rest is answered here from the copied numbers.
 */
public final class EmbassyFieldSVA {

    /** The kinds (EmbassyCompatSVA.HELI / SHORT / FULL). */
    public final int id, dim, kind, version, side;
    /** The ICAO the core proposes (X + the element's two letters + the number's letter; EmbassyAirportSVA makes it unique). */
    public final String icao;
    public final int coreX, coreZ, floorY, radius, gateDir;
    public final boolean alongX;
    /** FULL 9 / 201, SHORT 7 / 91, HELI 0 / 0. */
    public final int width, length;
    public final int headingLow, headingHigh;
    public final String numberLow, numberHigh;
    public final boolean ilsLow, ilsHigh;
    public final int adRadius;
    /** The core's AirfieldInfoSC this copy was made of (EmbassyCompatSVA casts it back for the shape questions; never null). */
    final Object handle;

    private final IChatComponent name;
    private final int[] runwayBox, stripBox, taxiBox, apronBox, towerDesk, beacon, terminalBox, towerBox, hangarBox, monumentBox, helipadBox,
            windsock, zoneBox;
    private final int[][] ends = new int[2][], thresholds = new int[2][], thresholdKeys = new int[2][], localizers = new int[2][],
            glideSlopes = new int[2][], pads = new int[2][];
    private final int[] landing = new int[2];
    /** lights[meta] for the core's light metas (LIGHT_EDGE .. LIGHT_TAXI), empty otherwise. */
    private final int[][][] lights = new int[9][][];
    private final int[][] funnels, approaches, spotBoxes, columns, adPosts;
    private final int[] spotKinds;
    private final String[] spotKeys;

    /** EmbassyCompatSVA's: every number given (arrays copied in). */
    EmbassyFieldSVA(Object handle, int id, int dim, int kind, int version, int side, String icao, IChatComponent name, int coreX, int coreZ,
            int floorY, int radius, int gateDir, boolean alongX, int width, int length, int headingLow, int headingHigh, String numberLow,
            String numberHigh, boolean ilsLow, boolean ilsHigh, int adRadius, int[] runwayBox, int[] stripBox, int[] taxiBox, int[] apronBox,
            int[] towerDesk, int[] beacon, int[] terminalBox, int[] towerBox, int[] hangarBox, int[] monumentBox, int[] helipadBox,
            int[] windsock, int[] zoneBox, int[][] funnels, int[][] approaches, int[][] spotBoxes, int[] spotKinds, String[] spotKeys,
            int[][] columns, int[][] adPosts) {
        this.handle = handle;
        this.id = id;
        this.dim = dim;
        this.kind = kind;
        this.version = version;
        this.side = side;
        this.icao = icao == null ? "" : icao;
        this.name = name == null ? new ChatComponentText("#" + id) : name;
        this.coreX = coreX;
        this.coreZ = coreZ;
        this.floorY = floorY;
        this.radius = radius;
        this.gateDir = gateDir;
        this.alongX = alongX;
        this.width = width;
        this.length = length;
        this.headingLow = headingLow;
        this.headingHigh = headingHigh;
        this.numberLow = numberLow == null ? "" : numberLow;
        this.numberHigh = numberHigh == null ? "" : numberHigh;
        this.ilsLow = ilsLow;
        this.ilsHigh = ilsHigh;
        this.adRadius = adRadius;
        this.runwayBox = copy(runwayBox);
        this.stripBox = copy(stripBox);
        this.taxiBox = copy(taxiBox);
        this.apronBox = copy(apronBox);
        this.towerDesk = copy(towerDesk);
        this.beacon = copy(beacon);
        this.terminalBox = copy(terminalBox);
        this.towerBox = copy(towerBox);
        this.hangarBox = copy(hangarBox);
        this.monumentBox = copy(monumentBox);
        this.helipadBox = copy(helipadBox);
        this.windsock = copy(windsock);
        this.zoneBox = copy(zoneBox);
        this.funnels = copy(funnels);
        this.approaches = copy(approaches);
        this.spotBoxes = copy(spotBoxes);
        this.spotKinds = spotKinds == null ? new int[0] : spotKinds.clone();
        this.spotKeys = spotKeys == null ? new String[0] : spotKeys.clone();
        this.columns = copy(columns);
        this.adPosts = copy(adPosts);
    }

    /** EmbassyCompatSVA's: the per-end numbers ([0] low, [1] high). */
    void setEnd(boolean low, int[] end, int[] threshold, int[] thresholdKey, int landingDistance, int[] localizer, int[] glideSlope, int[] pad) {
        int i = low ? 0 : 1;
        ends[i] = copy(end);
        thresholds[i] = copy(threshold);
        thresholdKeys[i] = copy(thresholdKey);
        landing[i] = landingDistance;
        localizers[i] = copy(localizer);
        glideSlopes[i] = copy(glideSlope);
        pads[i] = copy(pad);
    }

    /** EmbassyCompatSVA's: the core's lights of one BlockAirfieldSC meta. */
    void setLights(int meta, int[][] l) {
        if (meta >= 0 && meta < lights.length) {
            lights[meta] = copy(l);
        }
    }

    // ------------------------------------------------------------------ the copied numbers

    /** «Посольство „Тантал-1“» (each client translates it). */
    public IChatComponent name() {
        return name.createCopy();
    }

    public boolean full() {
        return kind == EmbassyCompatSVA.FULL;
    }

    /** It has a runway (FULL or SHORT; HELI - the helipad alone). */
    public boolean hasRunway() {
        return runwayBox != null;
    }

    public int[] runwayBox() {
        return copy(runwayBox);
    }

    public int[] stripBox() {
        return copy(stripBox);
    }

    /** The low / high end's middle block on the axis (null HELI). */
    public int[] end(boolean low) {
        return copy(ends[low ? 0 : 1]);
    }

    /** The earliest touch-down of a landing over that end: the threshold's middle block (null HELI). */
    public int[] threshold(boolean low) {
        return copy(thresholds[low ? 0 : 1]);
    }

    /** The threshold lights' row middle - the add-on's virtual runway end key (null HELI). */
    public int[] thresholdKey(boolean low) {
        return copy(thresholdKeys[low ? 0 : 1]);
    }

    /** From the threshold of a landing over that end to the far end (FULL 181, SHORT 91, HELI 0). */
    public int landingDistance(boolean low) {
        return landing[low ? 0 : 1];
    }

    /** The virtual localizer / glide slope of a landing over that end (null HELI). */
    public int[] localizer(boolean low) {
        return copy(localizers[low ? 0 : 1]);
    }

    public int[] glideSlope(boolean low) {
        return copy(glideSlopes[low ? 0 : 1]);
    }

    /** FULL's turning pad at that end (null otherwise). */
    public int[] padBox(boolean low) {
        return copy(pads[low ? 0 : 1]);
    }

    public int[] taxiBox() {
        return copy(taxiBox);
    }

    public int[] apronBox() {
        return copy(apronBox);
    }

    /** The core's flush lights of a BlockAirfieldSC meta (EmbassyCompatSVA.LIGHT_*) as blocks {x, floorY, z}; empty none. */
    public int[][] lights(int meta) {
        int[][] l = meta >= 0 && meta < lights.length ? lights[meta] : null;
        return l == null ? new int[0][] : copy(l);
    }

    public int[][] funnelBoxes() {
        return copy(funnels);
    }

    /** The neutral sky's approach corridors (low end's first; empty HELI). */
    public int[][] approachBoxes() {
        return copy(approaches);
    }

    /** The tower's desk {x, y, z} (null: SHORT / HELI - the virtual tower takes the helipad's column, columns()'s last). */
    public int[] towerDesk() {
        return copy(towerDesk);
    }

    public int[] beacon() {
        return copy(beacon);
    }

    public int[] terminalBox() {
        return copy(terminalBox);
    }

    public int[] towerBox() {
        return copy(towerBox);
    }

    public int[] hangarBox() {
        return copy(hangarBox);
    }

    public int[] monumentBox() {
        return copy(monumentBox);
    }

    /** The stands: FULL «МС-1» (the liner's), «МС-2..4», then the helipad «Н» (last, every kind). */
    public int spots() {
        return spotBoxes.length;
    }

    public int[] spotBox(int i) {
        return copy(spotBoxes[i]);
    }

    /** EmbassyCompatSVA.SPOT_LINER, SPOT_SMALL or SPOT_HELI. */
    public int spotKind(int i) {
        return spotKinds[i];
    }

    /** The stand's core lang key sc.airfield.spot.<key> («МС-1» .. «МС-4», «Н»). */
    public String spotKey(int i) {
        return spotKeys[i];
    }

    public int[] helipadBox() {
        return copy(helipadBox);
    }

    /** The «Колонки обслуживания» {x, y, z}, one a stand in spots' order (the helipad's last). */
    public int[][] columns() {
        return copy(columns);
    }

    /** The windsock mast's foot (null HELI). */
    public int[] windsock() {
        return copy(windsock);
    }

    /** The protected zone's bounding box (NOT the zone - EmbassyCompatSVA.inZone asks the shape). */
    public int[] zoneBox() {
        return copy(zoneBox);
    }

    /** The «Зенитный пост Наладчиков» guns {x, y, z} - the embassy's air defence fires from them. */
    public int[][] adPosts() {
        return copy(adPosts);
    }

    // ------------------------------------------------------------------ pure questions answered here

    /** The column is under the dome (the cylinder across). */
    public boolean inDome(double x, double z) {
        double dx = x - (coreX + 0.5D), dz = z - (coreZ + 0.5D);
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    /** The bare air defence circle (adRadius across from the core, any height) - the fire asks EmbassyCompatSVA.adZoneAt (bases left out). */
    public boolean inAdCircle(double x, double z) {
        double dx = x - (coreX + 0.5D), dz = z - (coreZ + 0.5D);
        return dx * dx + dz * dz <= (double) adRadius * adRadius;
    }

    /** Distance across from the core's column (blocks). */
    public double fromCore(double x, double z) {
        return Math.hypot(x - (coreX + 0.5D), z - (coreZ + 0.5D));
    }

    /** The column is on the paved runway. */
    public boolean onRunway(double x, double z) {
        return in(runwayBox, x, z);
    }

    /** The stand (spots() index) whose box holds the column, -1 none. */
    public int spotAt(double x, double z) {
        for (int i = 0; i < spotBoxes.length; i++) {
            if (in(spotBoxes[i], x, z)) {
                return i;
            }
        }
        return -1;
    }

    /** The column (x, z) lies in box {minX, minZ, maxX, maxZ} (inclusive blocks; null - never). */
    public static boolean in(int[] box, double x, double z) {
        if (box == null || box.length < 4) {
            return false;
        }
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        return bx >= box[0] && bx <= box[2] && bz >= box[1] && bz <= box[3];
    }

    @Override
    public String toString() {
        return "EmbassyField#" + id + " " + icao + " kind " + kind + " core " + coreX + " " + coreZ + " y " + floorY;
    }

    // ------------------------------------------------------------------ inside

    private static int[] copy(int[] a) {
        return a == null ? null : a.clone();
    }

    private static int[][] copy(int[][] a) {
        if (a == null) {
            return new int[0][];
        }
        int[][] out = new int[a.length][];
        for (int i = 0; i < a.length; i++) {
            out[i] = copy(a[i]);
        }
        return out;
    }
}
