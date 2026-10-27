package com.sva.airport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.LinerIls;

/**
 * Airport wave B2 (plan-next §9, item 29): the pure part of the «Диспетчерская вышка» - what makes an airport of it and the
 * small rules round it. No Minecraft classes (the dedicated server's self-test runs it).
 * <ul>
 * <li><b>Its runways</b>: every runway of two «Торец полосы» markers whose middle lies within RANGE (15 km-eq at the B1 scale)
 * of a tower belongs to the NEAREST such tower (assign()) - so two towers never share a runway and a runway far from any
 * tower keeps its ICAO placeholder «ZZxx».</li>
 * <li><b>The ICAO code</b>: four Latin letters (cleanIcao), unique in the world (all dimensions - unique()); a new tower
 * takes a free «Z» + three letters from its place (defaultIcao) until its owner names it.</li>
 * <li><b>The runway in use</b> (AUTO): the landing direction with the most head wind; within PREFER_KMH of the best one the
 * end with the better ILS wins (pickRunway) - the way a real tower turns the runway round only when the wind says so.</li>
 * <li><b>The «аэродром» zone</b> for the air defence (zone()): a circle round the runways' ends (ZONE_MARGIN more,
 * ZONE_MIN..ZoneMath.MAX_HALF), from the ground to the top, with the tower's transponder code range - wave A of the plan
 * lets the air defence treat those codes as its own; here they are stored and shown.</li>
 * <li><b>The callsign</b> of a target on the traffic screen and in the strips: the liner's flight number (MCDU INIT), else
 * its registration «B-» + the board number (the tower hears it on the radio whatever the transponder's mode), else the
 * squawk.</li>
 * </ul>
 */
public final class TowerMath {

    /** Runways within this many blocks of the tower (their middle) are its own (15 km-eq). */
    public static final double RANGE = 1500;
    /** The traffic screen's reach (blocks, 25 km-eq) and the ATIS / datalink reach (30 km-eq). */
    public static final double TRAFFIC_RANGE = 2500, ATC_RANGE = 3000;
    /** The «аэродром» zone: this far round the runways' ends, its radius limits. */
    public static final int ZONE_MARGIN = 120, ZONE_MIN = 96;
    /** The default transponder code range of a tower's arrivals / departures (octal 4100..4177). */
    public static final int CODE_LO = 04100, CODE_HI = 04177;
    /** AUTO runway: an end within this head-wind margin of the best one may win by its ILS (km/h). */
    public static final double PREFER_KMH = 10;
    /** The tower's name: at most this many characters. */
    public static final int NAME_MAX = 20;

    private TowerMath() {
    }

    // ---------------------------------------------------------------- the ICAO code

    /** Four Latin letters, upper case (Cyrillic look-alikes and anything else dropped); not four: "". */
    public static String cleanIcao(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if (c >= 'A' && c <= 'Z') {
                b.append(c);
            }
        }
        return b.length() == 4 ? b.toString() : "";
    }

    public static boolean validIcao(String s) {
        return s != null && s.length() == 4 && cleanIcao(s).equals(s);
    }

    /** `icao` is free among the other towers' codes (case-insensitive). */
    public static boolean unique(String icao, Collection<String> others) {
        for (String o : others) {
            if (o != null && o.equalsIgnoreCase(icao)) {
                return false;
            }
        }
        return true;
    }

    /** A free code for a new tower at (x, z): «Z» + three letters from the place, the next free one if taken. */
    public static String defaultIcao(int x, int z, Collection<String> taken) {
        int h = Math.abs(x * 73856093 ^ z * 19349663) % (26 * 26 * 26);
        for (int i = 0; i < 26 * 26 * 26; i++) {
            int k = (h + i) % (26 * 26 * 26);
            String c = "Z" + (char) ('A' + k / 676) + (char) ('A' + k / 26 % 26) + (char) ('A' + k % 26);
            if (!c.startsWith("ZZ") && unique(c, taken)) {
                return c;
            }
        }
        return "ZAAA";
    }

    /** The tower's name: printable, at most NAME_MAX characters, trimmed. */
    public static String cleanName(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < NAME_MAX; i++) {
            char c = s.charAt(i);
            if (c >= ' ' && c != '§' && c != '|' && c != '\u0001' && c != '\u0002') {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    // ---------------------------------------------------------------- the runways

    /** A tower's place for the assignment: dimension, x, z; embassy airfield: the embassy's id of its virtual tower (-1 a player's). */
    public static final class Site {
        public final int dim, emb;
        public final double x, z;

        public Site(int dim, double x, double z) {
            this(dim, x, z, -1);
        }

        public Site(int dim, double x, double z, int emb) {
            this.dim = dim;
            this.x = x;
            this.z = z;
            this.emb = emb < 0 ? -1 : emb;
        }
    }

    /** The runway's middle. */
    public static double[] middle(IlsPairing.Rwy r) {
        return new double[] {(r.a.x + r.b.x) / 2, (r.a.z + r.b.z) / 2};
    }

    /** Per runway: the index of the tower it belongs to (the nearest within RANGE of its middle), -1 none. */
    public static int[] assign(List<Site> towers, List<IlsPairing.Rwy> rws) {
        return assign(towers, rws, null);
    }

    /**
     * Embassy airfield (docs/plan-embassy-airfield-addon.md §3.3): as assign(), both ways - an embassy's runway (rwyEmb[i] its id) goes
     * only to that embassy's tower (Site.emb), a player's runway (-1) never to an embassy's tower. `rwyEmb` null: every runway a player's.
     */
    public static int[] assign(List<Site> towers, List<IlsPairing.Rwy> rws, int[] rwyEmb) {
        int[] out = new int[rws.size()];
        for (int i = 0; i < rws.size(); i++) {
            IlsPairing.Rwy r = rws.get(i);
            int emb = rwyEmb == null || i >= rwyEmb.length || rwyEmb[i] < 0 ? -1 : rwyEmb[i];
            double[] m = middle(r);
            int best = -1;
            double bd = RANGE * RANGE;
            for (int k = 0; k < towers.size(); k++) {
                Site t = towers.get(k);
                if (t.dim != r.a.dim || t.emb != emb) {
                    continue;
                }
                double d = (t.x - m[0]) * (t.x - m[0]) + (t.z - m[1]) * (t.z - m[1]);
                if (d <= bd) {
                    bd = d;
                    best = k;
                }
            }
            out[i] = best;
        }
        return out;
    }

    /** One landing direction offered to pickRunway: its name, landing course, the ILS category there (0: none / off). */
    public static final class End {
        public final String name;
        public final double course;
        public final int cat;

        public End(String name, double course, int cat) {
            this.name = name;
            this.course = course;
            this.cat = cat;
        }
    }

    /** The head-wind component (km/h, + head) of a wind from `from` at `kmh` on a landing course. */
    public static double headwind(double from, double kmh, double course) {
        return kmh * Math.cos(Math.toRadians(CruiseMath.wrap(from - course)));
    }

    /**
     * AUTO: the runway in use - the most head wind (a calm or no weather station: the best ILS, then the first); within
     * PREFER_KMH of the best head wind the better ILS wins. @return the index into `ends`, -1 when empty
     */
    public static int pickRunway(List<End> ends, double windFrom, double windKmh) {
        if (ends.isEmpty()) {
            return -1;
        }
        boolean calm = windFrom < 0 || windKmh < 3;
        double best = -Double.MAX_VALUE;
        for (End e : ends) {
            best = Math.max(best, calm ? 0 : headwind(windFrom, windKmh, e.course));
        }
        int pick = -1;
        for (int i = 0; i < ends.size(); i++) {
            End e = ends.get(i);
            double hw = calm ? 0 : headwind(windFrom, windKmh, e.course);
            if (hw < best - PREFER_KMH) {
                continue;
            }
            if (pick < 0) {
                pick = i;
                continue;
            }
            End p = ends.get(pick);
            double hp = calm ? 0 : headwind(windFrom, windKmh, p.course);
            if (e.cat > p.cat || e.cat == p.cat && hw > hp + 1e-9) {
                pick = i;
            }
        }
        return pick;
    }

    // ---------------------------------------------------------------- the air-defence zone

    /**
     * The tower's «аэродром» zone: a circle centred on the middle of its runways' ends (the tower's place without runways),
     * reaching ZONE_MARGIN past the farthest end, ZONE_MIN..ZoneMath.MAX_HALF; 0..255 high; the tower's code range; shown to
     * pilots, no alarm. `name` - the ICAO code (the zone's name, the airport's ident in the MCDU), `kp` - what the air defence
     * shows as the zone's owner post (the tower's name).
     */
    public static ZoneMath.Zone zone(String name, String kp, String owner, int dim, int tx, int ty, int tz, List<IlsPairing.Rwy> rws, int codeLo,
            int codeHi) {
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.name = name;
        z.type = ZoneMath.AERODROME;
        z.shape = ZoneMath.CIRCLE;
        double cx = tx + 0.5, cz = tz + 0.5;
        if (!rws.isEmpty()) {
            cx = 0;
            cz = 0;
            for (IlsPairing.Rwy r : rws) {
                cx += r.a.x + r.b.x;
                cz += r.a.z + r.b.z;
            }
            cx /= 2.0 * rws.size();
            cz /= 2.0 * rws.size();
        }
        double far = 0;
        for (IlsPairing.Rwy r : rws) {
            far = Math.max(far, Math.hypot(r.a.x - cx, r.a.z - cz));
            far = Math.max(far, Math.hypot(r.b.x - cx, r.b.z - cz));
        }
        z.cx = (int) Math.floor(cx);
        z.cz = (int) Math.floor(cz);
        z.r = rws.isEmpty() ? ZONE_MIN : (int) Math.max(ZONE_MIN, Math.min(ZoneMath.MAX_HALF, Math.ceil(far + ZONE_MARGIN)));
        z.lo = 0;
        z.hi = ZoneMath.ALT_MAX;
        z.codeLo = Math.max(0, Math.min(XpdrMath.MAX, Math.min(codeLo, codeHi)));
        z.codeHi = Math.max(z.codeLo, Math.min(XpdrMath.MAX, Math.max(codeLo, codeHi)));
        z.show = true;
        z.alarm = false;
        z.kp = kp;
        z.owner = owner;
        z.kx = tx;
        z.ky = ty;
        z.kz = tz;
        z.dim = dim;
        return z;
    }

    /** Two zones the same for the air defence's copy (no re-publish, no version churn). */
    public static boolean sameZone(ZoneMath.Zone a, ZoneMath.Zone b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.name.equals(b.name) && a.cx == b.cx && a.cz == b.cz && a.r == b.r && a.codeLo == b.codeLo && a.codeHi == b.codeHi && a.kp.equals(b.kp)
                && a.owner.equals(b.owner) && a.dim == b.dim && a.kx == b.kx && a.ky == b.ky && a.kz == b.kz;
    }

    /** A code range from the screen: both valid octal codes, low <= high, none of the special ones inside. @return {lo, hi} or null */
    public static int[] codeRange(int lo, int hi) {
        if (!XpdrMath.valid(lo) || !XpdrMath.valid(hi)) {
            return null;
        }
        int a = Math.min(lo, hi), b = Math.max(lo, hi);
        for (int sp : new int[] {XpdrMath.EMERGENCY, XpdrMath.NO_RADIO, XpdrMath.HIJACK, XpdrMath.VFR}) {
            if (sp >= a && sp <= b) {
                return null;
            }
        }
        return new int[] {a, b};
    }

    // ---------------------------------------------------------------- the traffic

    /** A target's callsign: the flight number, else «B-n» (its board number), else the squawk, else «----». */
    public static String callsign(String flt, int mode, int board, int code) {
        if (flt != null && flt.trim().length() > 0) {
            return flt.trim();
        }
        if (board > 0) {
            return "B-" + board;
        }
        return code >= 0 ? XpdrMath.format(code) : "----";
    }

    /** The heading's three digits (0 as 360). */
    public static String three(double deg) {
        int d = (int) Math.round(LinerIls.wrap360(deg));
        d = d == 0 ? 360 : d;
        return (d < 100 ? "0" : "") + (d < 10 ? "0" : "") + d;
    }

    /** The airports a list of towers names: their ICAO codes (the others' list for unique()). */
    public static List<String> codes(List<String[]> towers) {
        List<String> l = new ArrayList<String>();
        for (String[] t : towers) {
            l.add(t[0]);
        }
        return l;
    }
}
