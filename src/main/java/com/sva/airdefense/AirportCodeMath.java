package com.sva.airdefense;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Wave A (plan-next §9 A4, «аэродромные коды»): an airport's transponder codes - pure, the self-test checks it.
 * <ul>
 * <li><b>Assignment</b>: the tower (its «аэродром» zone keeps the range, default 4100..4177) gives a departing aircraft (the
 * take-off / line-up clearance) and an arriving one (entering the zone with mode C / S on) the first free code of its
 * range - one nobody near the tower squawks or holds already, none of the special ones (assign). The liner gets it as a
 * CPDLC uplink «КОД ОТВЕТЧИКА 41xx» (WILCO sets it); the others get it set at once (airdefense.autoSquawk) or told.</li>
 * <li><b>Friends</b>: the air defence takes an aircraft squawking a code of a FRIENDLY airport's range inside the airport's
 * zone or within NEAR blocks of it as its own («свой»: no alarm, no warnings, no fire). Friendly: the airport's owner is the
 * post's owner or on its access list, or the post trusts it by name (the ICAO code or the tower's name in its settings,
 * «Доверенные аэродромы»).</li>
 * </ul>
 */
public final class AirportCodeMath {

    /** «Near the airport»: this far outside its zone still counts (blocks). */
    public static final double NEAR = 160;
    /** The trusted airports a post keeps at most. */
    public static final int MAX_TRUSTED = 12;

    private AirportCodeMath() {
    }

    /** A code the tower may give: valid, not 0000 / 7000 / 7500 / 7600 / 7700 / the 77xx block. */
    public static boolean assignable(int code) {
        return XpdrMath.valid(code) && !XpdrMath.reserved(code);
    }

    /**
     * The code for an aircraft: `keep` when it is in [lo, hi], assignable and not used by another; else the first free one
     * of the range from `lo` (lo / hi in either order). @return the code, -1 when the range is full
     */
    public static int assign(int lo, int hi, Collection<Integer> used, int keep) {
        int a = Math.max(0, Math.min(lo, hi)), b = Math.min(XpdrMath.MAX, Math.max(lo, hi));
        if (keep >= a && keep <= b && assignable(keep) && (used == null || !used.contains(Integer.valueOf(keep)))) {
            return keep;
        }
        for (int c = a; c <= b; c++) {
            if (assignable(c) && (used == null || !used.contains(Integer.valueOf(c)))) {
                return c;
            }
        }
        return -1;
    }

    /** «UBTW, Северный» -> [UBTW, СЕВЕРНЫЙ]: names split by commas / spaces / semicolons, upper case, at most MAX_TRUSTED. */
    public static List<String> parseTrusted(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) {
            return out;
        }
        for (String p : s.split("[,; ]+")) {
            String t = p.trim().toUpperCase(java.util.Locale.ROOT);
            if (t.length() > 0 && t.length() <= 24 && !out.contains(t) && out.size() < MAX_TRUSTED) {
                out.add(t);
            }
        }
        return out;
    }

    /** The list back as the settings field shows it. */
    public static String joinTrusted(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) {
            b.append(b.length() > 0 ? ", " : "").append(s);
        }
        return b.toString();
    }

    /**
     * Is the airport (its zone: name = the ICAO code, kp = the tower's name, owner) friendly to a post of `kpOwner` with that
     * access list and trusted names?
     */
    public static boolean friendly(ZoneMath.Zone apt, String kpOwner, Collection<String> access, Collection<String> trusted) {
        if (apt == null || apt.type != ZoneMath.AERODROME) {
            return false;
        }
        String o = apt.owner == null ? "" : apt.owner;
        if (o.length() > 0 && o.equalsIgnoreCase(kpOwner == null ? "" : kpOwner)) {
            return true;
        }
        if (access != null && o.length() > 0) {
            for (String a : access) {
                if (a != null && a.equalsIgnoreCase(o)) {
                    return true;
                }
            }
        }
        if (trusted != null) {
            for (String t : trusted) {
                if (t != null && t.length() > 0 && (t.equalsIgnoreCase(apt.name) || t.equalsIgnoreCase(apt.kp))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Inside the airport's zone (its band) or within NEAR of its outline. */
    public static boolean nearAirport(ZoneMath.Zone apt, double x, double y, double z, boolean altKnown) {
        return ZoneMath.contains(apt, x, y, z, altKnown) || ZoneMath.altIn(apt, y, altKnown) && ZoneMath.distance2D(apt, x, z) <= NEAR;
    }

    /**
     * The friendly airport whose code the flight squawks, in / near which it is - null none (then the usual classes).
     * `zones`: every zone known (the aerodromes are picked out).
     */
    public static ZoneMath.Zone friendOf(List<ZoneMath.Zone> zones, int code, double x, double y, double z, boolean altKnown, String kpOwner,
            Collection<String> access, Collection<String> trusted) {
        if (zones == null || !XpdrMath.valid(code)) {
            return null;
        }
        for (ZoneMath.Zone a : zones) {
            if (ZoneMath.aerodromeCode(a, code) && friendly(a, kpOwner, access, trusted) && nearAirport(a, x, y, z, altKnown)) {
                return a;
            }
        }
        return null;
    }
}
