package com.sva.airport;

import java.util.ArrayList;
import java.util.List;

/**
 * Airport wave B1 (plan-next §9, item 39): «Планшет строителя аэродрома» - where everything of an ILS runway goes, pure (the
 * tablet's hologram, client.AirportPlannerSVA, draws these items; the self-test checks the numbers). Given a runway by its
 * two ends (the first one clicked is the landing threshold: the approach comes over it toward the second), plan() lays out
 * in that end's frame (along from the threshold, across + right of the landing direction; AptScale 1 block = 10 m-eq):
 * <ul>
 * <li>the localizer array LOC_BEYOND past the far end on the centre line (5 x 1 across) and its shelter beside it, behind
 * its critical area;</li>
 * <li>the glide slope mast GS_SETBACK past the threshold, GS_OFFSET to the right (TCH ~15 m-eq - IlsBeam.tch) and its
 * shelter just past it (out of its critical area);</li>
 * <li>the markers OM / MM / IM on the extended centre line (MarkerMath.NOMINAL);</li>
 * <li>the RVR transmissometers TDZ / MID / END on the left, RVR_OUT off the edge;</li>
 * <li>the PAPI row on the left opposite the glide slope's touchdown (PAPI_ALONG), the weather station with its windsock;</li>
 * <li>the approach lights (RunwayLayout.ALS_LEN) and the edge lights (the runway's own, shown for the look);</li>
 * <li>the critical / sensitive areas (IlsCat) - the hologram paints them red where a block or a vehicle stands;</li>
 * <li>the category expected with all of it in place and calibrated (expected()).</li>
 * </ul>
 * thresholdBlocks() is the tablet's «поставить торцы»: the two marker blocks one block beyond each end of a strip of
 * runway blocks, on its centre line.
 */
public final class PlannerMath {

    /** The planned places (blocks). */
    public static final double LOC_BEYOND = 30, GS_SETBACK = 25, GS_OFFSET = 15, RVR_OUT = 6, PAPI_ALONG = 30, PAPI_OUT = 2, WX_ALONG = 60,
            WX_OUT = 14, SHELTER_GAP = 4;
    /** The localizer's shelter beside its array (along past it, across): within the shelter's reach of the middle element. */
    public static final double LOC_SHELTER_ALONG = 2, LOC_SHELTER_ACROSS = 4;
    /** Item kinds (lang sva.apt.plan.KEY). */
    public static final int LOC = 0, LOC_SHELTER = 1, GS = 2, GS_SHELTER = 3, MARKER = 4, RVR = 5, PAPI = 6, WEATHER = 7, WINDSOCK = 8, ALS = 9,
            EDGE = 10, LOC_CRIT = 11, GS_CRIT = 12, LOC_SENS = 13, GS_SENS = 14;
    public static final String[] KEYS = {"loc", "locShelter", "gs", "gsShelter", "marker", "rvr", "papi", "weather", "windsock", "als", "edge",
        "locCrit", "gsCrit", "locSens", "gsSens"};

    private PlannerMath() {
    }

    /** One planned thing: its kind, its box in the landing end's frame (along a0..a1, across c0..c1), its label's argument. */
    public static final class Item {
        public final int kind;
        public final double a0, a1, c0, c1;
        public final String arg;

        Item(int kind, double a0, double a1, double c0, double c1, String arg) {
            this.kind = kind;
            this.a0 = Math.min(a0, a1);
            this.a1 = Math.max(a0, a1);
            this.c0 = Math.min(c0, c1);
            this.c1 = Math.max(c0, c1);
            this.arg = arg;
        }

        /** A one-block thing centred at (along, across). */
        static Item at(int kind, double along, double across, String arg) {
            return new Item(kind, along - 0.5, along + 0.5, across - 0.5, across + 0.5, arg);
        }

        public double along() {
            return (a0 + a1) / 2;
        }

        public boolean contains(double along, double across) {
            return along >= a0 && along <= a1 && across >= c0 && across <= c1;
        }

        public double across() {
            return (c0 + c1) / 2;
        }
    }

    /** Everything for a runway `len` long, `width` wide, landing at the first end; glide angle (deg). */
    public static List<Item> plan(double len, int width, double angle) {
        List<Item> l = new ArrayList<Item>();
        double la = len + LOC_BEYOND;
        l.add(new Item(LOC, la - 0.5, la + 0.5, -2.5, 2.5, ""));
        l.add(Item.at(LOC_SHELTER, la + LOC_SHELTER_ALONG, LOC_SHELTER_ACROSS, ""));
        l.add(Item.at(GS, GS_SETBACK, GS_OFFSET, String.valueOf(angle)));
        l.add(Item.at(GS_SHELTER, GS_SETBACK + SHELTER_GAP, GS_OFFSET, ""));
        for (int t = MarkerMath.OM; t <= MarkerMath.IM; t++) {
            l.add(Item.at(MARKER, -MarkerMath.NOMINAL[t], 0, MarkerMath.NAMES[t]));
        }
        double side = -(width / 2.0 + RVR_OUT);
        l.add(Item.at(RVR, 30, side, "TDZ"));
        l.add(Item.at(RVR, len / 2, side, "MID"));
        l.add(Item.at(RVR, len - 30, side, "END"));
        for (int k = 0; k < 4; k++) {
            l.add(Item.at(PAPI, PAPI_ALONG, -(width / 2.0 + PAPI_OUT + k + 0.5), String.valueOf(k + 1)));
        }
        l.add(Item.at(WEATHER, WX_ALONG, -(width / 2.0 + WX_OUT), ""));
        l.add(Item.at(WINDSOCK, WX_ALONG + 4, -(width / 2.0 + WX_OUT - 2), ""));
        l.add(new Item(ALS, -RunwayLayout.ALS_LEN, -1, -0.5, 0.5, ""));
        for (double v = 0; v <= len; v += RunwayLayout.EDGE_STEP * 4) {
            l.add(Item.at(EDGE, v, width / 2.0 + 0.5, ""));
            l.add(Item.at(EDGE, v, -width / 2.0 - 0.5, ""));
        }
        IlsCat.Area a = IlsCat.locCritical(la, 0);
        l.add(new Item(LOC_CRIT, a.a0, a.a1, a.c0, a.c1, ""));
        a = IlsCat.locSensitive(la, 0);
        l.add(new Item(LOC_SENS, a.a0, a.a1, a.c0, a.c1, ""));
        a = IlsCat.gsCritical(GS_SETBACK, GS_OFFSET, width);
        l.add(new Item(GS_CRIT, a.a0, a.a1, a.c0, a.c1, ""));
        a = IlsCat.gsSensitive(GS_SETBACK, GS_OFFSET, width);
        l.add(new Item(GS_SENS, a.a0, a.a1, a.c0, a.c1, ""));
        return l;
    }

    /** The planned station's setup: everything in place, monitored, powered and calibrated; the areas' occupants as found. */
    public static IlsCat.Setup setup(double angle, int locCrit, int gsCrit, int locSens, int gsSens) {
        IlsCat.Setup u = new IlsCat.Setup();
        u.locLat = 0;
        u.locBeyond = LOC_BEYOND;
        u.gsSetback = GS_SETBACK;
        u.gsOffset = GS_OFFSET;
        u.angle = angle;
        u.locCrit = locCrit;
        u.gsCrit = gsCrit;
        u.locSens = locSens;
        u.gsSens = gsSens;
        u.calibCat = 3;
        return u;
    }

    /** The category the plan gives with these occupants (3 when the areas are clear). */
    public static int expected(double angle, int locCrit, int gsCrit, int locSens, int gsSens) {
        return IlsCat.compute(setup(angle, locCrit, gsCrit, locSens, gsSens)).cat;
    }

    /**
     * The tablet's «поставить торцы» for a strip of runway blocks along X (else Z) with its centre line at `c` (the strip's
     * cross coordinate, block edges), its blocks a0..a1 (block edges) and its top block's Y: the two marker blocks {x, y, z}
     * one block beyond each end, on the centre line (an even width: the block just left of it as seen from a0).
     */
    public static int[][] thresholdBlocks(boolean alongX, double c, double a0, double a1, int y) {
        int k = (int) Math.floor(c - 0.01);
        int b0 = (int) Math.floor(a0) - 1, b1 = (int) Math.floor(a1);
        return alongX ? new int[][] {{b0, y, k}, {b1, y, k}} : new int[][] {{k, y, b0}, {k, y, b1}};
    }
}
