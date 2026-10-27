package com.sva.airport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.sva.vehicle.LinerIls;

/**
 * PACKAGE A (docs/plan-embassy-airfield-addon.md §3.3): the pure rules of the embassies' virtual airports - the keys, the ICAO code, the
 * ILS ident and frequency, the runway ends' elevation, the places the add-on puts its virtual taxi lights and its PAPI at, the client's
 * runway words ("Ext", "Ils"). No Minecraft classes (the dedicated server's self-test runs them). Written by the coordinator with working
 * first bodies; refined by A (the signatures stay). Boxes {minX, minZ, maxX, maxZ} inclusive and points {x, y, z} - the core's
 * (EmbassyFieldSVA); world blocks.
 */
public final class EmbassyAirportMath {

    /** An embassy's ICAO starts with this letter; new player towers may not take it (the user's choice 10). */
    public static final char EMBASSY_PREFIX = 'X';
    /** The ILS frequencies an embassy uses: 108.10 + 0.05 x (2 x id + end) within FREQ_SLOTS slots (hundredths). */
    public static final int FREQ_SLOTS = 78;
    /** Taxiway «А»'s holding point (the stop bar): this many blocks past the +a turning pad's far edge, on the taxiway's centre line. */
    public static final int HOLD_OFF = 2;

    private EmbassyAirportMath() {
    }

    /** "dim:x:y:z" of a point {x, y, z} (AirportDataSVA.key's format - IlsPairing / TileEntityIlsAntennaSVA.parse read it); null - null. */
    public static String key(int dim, int[] p) {
        return p == null || p.length < 3 ? null : dim + ":" + p[0] + ":" + p[1] + ":" + p[2];
    }

    /** A new PLAYER tower may take this code (four Latin letters, not the embassies' X...). Old player towers keep theirs. */
    public static boolean playerIcaoAllowed(String icao) {
        if (icao == null || icao.length() != 4) {
            return false;
        }
        String c = icao.toUpperCase();
        return TowerMath.validIcao(c) && c.charAt(0) != EMBASSY_PREFIX;
    }

    /**
     * The embassy's code: the core's proposal when free among `taken` (any tower's, case-insensitive), else the next free one counting the
     * last letter on, then the third (X + the element's letters kept first); "" none free (never in practice: 676 codes an element) or a
     * proposal that is no ICAO code at all.
     */
    public static String uniqueIcao(String proposed, Collection<String> taken) {
        if (proposed == null || proposed.length() != 4) {
            return "";
        }
        String p = proposed.toUpperCase();
        if (!TowerMath.validIcao(p)) {
            return "";
        }
        if (TowerMath.unique(p, taken)) {
            return p;
        }
        int start = (p.charAt(2) - 'A') * 26 + (p.charAt(3) - 'A');
        for (int i = 1; i < 26 * 26; i++) {
            int k = (start + i) % (26 * 26);
            String c = p.substring(0, 2) + (char) ('A' + k / 26) + (char) ('A' + k % 26);
            if (TowerMath.unique(c, taken)) {
                return c;
            }
        }
        return "";
    }

    /** The localizer's ident: «I» + the code's last three letters (XTAA - ITAA). */
    public static String locIdent(String icao) {
        return icao == null || icao.length() != 4 ? "IEMB" : "I" + icao.substring(1).toUpperCase();
    }

    /** The localizer's frequency of embassy `id`'s end (low / high): its own slot of 0.05 MHz (LinerIls.clampFreq's grid). */
    public static int locFreq(int id, boolean low) {
        int slot = (Math.abs(id % FREQ_SLOTS) * 2 + (low ? 0 : 1)) % FREQ_SLOTS;
        return LinerIls.clampFreq(LinerIls.FREQ_MIN + slot * 5);
    }

    /** A runway end's surface elevation: the paving's top (its blocks lie at floorY). */
    public static double endElev(int floorY) {
        return floorY + 1;
    }

    // ------------------------------------------------------------------ the add-on's places on the core's field

    /** A box's middle block {x, z} (null - null). */
    public static int[] middle(int[] box) {
        return box == null || box.length < 4 ? null : new int[] {half(box[0] + box[2]), half(box[1] + box[3])};
    }

    /**
     * Taxiway «А»'s centre-line points {x, z} abreast the core's taxi edge lamps (EmbassyFieldSVA.lights(LIGHT_TAXI) - both edges every
     * LIGHT_STEP): one a lamp row, in the lamps' order - the virtual K_TAXI lights of the follow-me graph. Empty: no taxiway.
     */
    public static List<int[]> taxiCentre(int[][] lamps, int[] taxi, boolean alongX) {
        List<int[]> out = new ArrayList<int[]>();
        int[] m = middle(taxi);
        if (m == null || lamps == null) {
            return out;
        }
        for (int[] l : lamps) {
            if (l == null || l.length < 3) {
                continue;
            }
            int[] c = alongX ? new int[] {l[0], m[1]} : new int[] {m[0], l[2]};
            boolean seen = false;
            for (int[] o : out) {
                seen |= o[0] == c[0] && o[1] == c[1];
            }
            if (!seen) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Taxiway «А»'s holding point {x, z} (the virtual stop bar, K_STOP): on the taxiway's centre line HOLD_OFF past the far edge of the +a
     * end's turning pad `pad` (where the taxiway leaves it); `plusA` +1 / -1 - the +a direction along the runway's world axis (X when
     * `alongX`). Null: no pad / taxiway (SHORT, HELI).
     */
    public static int[] holdPoint(int[] pad, int[] taxi, boolean alongX, int plusA) {
        int[] m = middle(taxi);
        if (m == null || pad == null || pad.length < 4) {
            return null;
        }
        int s = plusA < 0 ? -1 : 1;
        if (alongX) {
            return new int[] {(s > 0 ? pad[2] : pad[0]) + s * HOLD_OFF, m[1]};
        }
        return new int[] {m[0], (s > 0 ? pad[3] : pad[1]) + s * HOLD_OFF};
    }

    /** The +a direction (+1 / -1) along the runway's world axis from its low end to its high end (both {x, y, z}; +1 without them). */
    public static int plusA(int[] lowEnd, int[] highEnd, boolean alongX) {
        if (lowEnd == null || highEnd == null || lowEnd.length < 3 || highEnd.length < 3) {
            return 1;
        }
        int d = alongX ? highEnd[0] - lowEnd[0] : highEnd[2] - lowEnd[2];
        return d < 0 ? -1 : 1;
    }

    // ------------------------------------------------------------------ the clients' runway words (AirportDataSVA.clientData)

    /** "Ext": the paved blocks from the threshold lights' row (the virtual runway end) out to the paved end - FULL 20, SHORT 1; 0 none. */
    public static int ext(int[] end, int[] thresholdKey) {
        if (end == null || thresholdKey == null || end.length < 3 || thresholdKey.length < 3) {
            return 0;
        }
        return Math.max(Math.abs(end[0] - thresholdKey[0]), Math.abs(end[2] - thresholdKey[2]));
    }

    /** "Ils": bit 1 - an ILS over the runway's end A, bit 2 - over its end B (A the field's low end or not). */
    public static int ilsBits(boolean aIsLow, boolean ilsLow, boolean ilsHigh) {
        boolean a = aIsLow ? ilsLow : ilsHigh, b = aIsLow ? ilsHigh : ilsLow;
        return (a ? 1 : 0) | (b ? 2 : 0);
    }

    /**
     * "EmbPapi" of a landing over an end: {x, y, z, heading x 10, angle x 10} - the block of the PAPI's inner box, EmbassyLightsMath.
     * PAPI_ALONG past the threshold (its lights' row middle `thresholdKey`; y - that block's, the paving's floorY) and PAPI_OFF out of the
     * paved edge (to the nearest block's middle - half a block further on an odd width) on the LEFT of the landing `heading` (true,
     * compass: 0 north = -z, 90 east = +x; given 1..360); `angle` the glide angle the boxes are set to (deg). Null: no end.
     */
    public static int[] papi(int[] thresholdKey, int heading, int width, double angle) {
        if (thresholdKey == null || thresholdKey.length < 3) {
            return null;
        }
        double h = Math.toRadians(heading), along = EmbassyLightsMath.PAPI_ALONG, out = width / 2.0 + EmbassyLightsMath.PAPI_OFF;
        double dx = along * Math.sin(h) - out * Math.cos(h), dz = -along * Math.cos(h) - out * Math.sin(h);
        int hdg = (heading % 360 + 360) % 360;
        return new int[] {thresholdKey[0] + away(dx), thresholdKey[1], thresholdKey[2] + away(dz), (hdg == 0 ? 360 : hdg) * 10,
            (int) Math.round(angle * 10)};
    }

    /** n / 2 rounded down (negative coordinates too). */
    private static int half(int n) {
        return n >= 0 ? n / 2 : -((-n + 1) / 2);
    }

    /** The nearest whole number, a half away from zero (both sides of a runway alike; the sines' last-digit noise ignored). */
    private static int away(double v) {
        long r = Math.round(Math.abs(v) + 1e-9);
        return (int) (v < 0 ? -r : r);
    }
}
