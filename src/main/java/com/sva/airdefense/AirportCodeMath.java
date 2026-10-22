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
 * post's owner or on its access list, or the post trusts it («Доверенные аэродромы»).</li>
 * </ul>
 * Audit 2026-10-22 (bugs 5, 6, 7, 9) - the codes were a cheap way to become «свой»:
 * <ul>
 * <li>a tower gives its codes only to the aircraft of its owner, his access list or the tower's own «коды: доверенные»
 * list (mayAssign), or when the controller in its seat clears / gives one by hand; it never replaces 7500 / 7600 / 7700 nor
 * a running «ключ дня» (mayReplace);</li>
 * <li>a tower's code counts only when that tower gave it to THIS aircraft (friendOf's Issued: the ATC session's code) - a
 * code dialled by hand, another's code, a code it was never given are no friends;</li>
 * <li>trust is by the tower's ICAO code bound to its owner («UBTW:Alice»; a bare «UBTW» is bound to the tower's owner when
 * the settings are saved) and only for the towers' zones (Zone.tower) - not by free text, not for the command posts'
 * aerodrome zones (anyone can name one «UBTW»); the list splits on commas / semicolons only.</li>
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

    /**
     * «ubtw, UBTX:Alice; Северный аэропорт» -> [UBTW, UBTX:Alice, СЕВЕРНЫЙ АЭРОПОРТ]: entries split by commas / semicolons
     * (not spaces - audit 2026-10-22), the ICAO part upper case, the owner after «:» as typed, at most MAX_TRUSTED, no two
     * for the same ICAO code.
     */
    public static List<String> parseTrusted(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) {
            return out;
        }
        List<String> seen = new ArrayList<String>();
        for (String p : s.split("[,;]+")) {
            String t = p.trim();
            int c = t.indexOf(':');
            String icao = (c < 0 ? t : t.substring(0, c)).trim().toUpperCase(java.util.Locale.ROOT);
            String owner = c < 0 ? "" : t.substring(c + 1).replace(":", "").trim();
            if (icao.length() == 0 || icao.length() > 24 || owner.length() > 32 || seen.contains(icao) || out.size() >= MAX_TRUSTED) {
                continue;
            }
            seen.add(icao);
            out.add(owner.length() > 0 ? icao + ":" + owner : icao);
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

    /** A trusted entry's ICAO code. */
    public static String icaoOf(String entry) {
        int c = entry == null ? -1 : entry.indexOf(':');
        return entry == null ? "" : c < 0 ? entry : entry.substring(0, c);
    }

    /** A trusted entry's owner ("" - not bound yet: it trusts nobody). */
    public static String ownerOf(String entry) {
        int c = entry == null ? -1 : entry.indexOf(':');
        return c < 0 ? "" : entry.substring(c + 1);
    }

    /** An unbound entry bound to the owner of the tower with its ICAO code (no such tower: it stays unbound). */
    public static String bind(String entry, String towerOwner) {
        if (ownerOf(entry).length() > 0 || towerOwner == null || towerOwner.length() == 0) {
            return entry;
        }
        return icaoOf(entry) + ":" + towerOwner;
    }

    /**
     * Audit review 2026-10-22: may this bare entry be bound now? Only when it is newly typed - not when the post kept it bare
     * from an earlier save (`before`: the entries saved so far; null - all may, the update of an older save). A bare «UBTW»
     * kept waiting used to be bound at the next save of ANY setting to whoever held the ICAO by then - one who took it after
     * the owner had typed it, silently.
     */
    public static boolean mayBind(String entry, Collection<String> before) {
        if (ownerOf(entry).length() > 0) {
            return false;
        }
        if (before != null) {
            for (String b : before) {
                if (b != null && ownerOf(b).length() == 0 && icaoOf(b).equalsIgnoreCase(icaoOf(entry))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Audit review 2026-10-22: a Mode S white-list entry's owner kept from a save older than the owner binding - any owner (as
     * 0.1.1 did) until the post's settings are saved again; never typed in (the screen's «23:*» goes back as a bare number).
     */
    public static final String ANY_OWNER = "*";

    /** A white-list entry (board, its owner) matches this aircraft: the board and the owner (ANY_OWNER: any; "" - nobody). */
    public static boolean whiteMatch(int board, String entryOwner, int qBoard, String qOwner) {
        if (board != qBoard || entryOwner == null || entryOwner.length() == 0) {
            return false;
        }
        return ANY_OWNER.equals(entryOwner) || entryOwner.equalsIgnoreCase(qOwner == null ? "" : qOwner);
    }

    /** The post trusts the tower with this ICAO code of this owner (a bound entry - both match). */
    public static boolean trustedTower(Collection<String> trusted, String icao, String owner) {
        if (trusted == null || icao == null || owner == null || owner.length() == 0) {
            return false;
        }
        for (String t : trusted) {
            String o = ownerOf(t);
            if (o.length() > 0 && icaoOf(t).equalsIgnoreCase(icao) && o.equalsIgnoreCase(owner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Is the airport (its zone: name = the ICAO code, kp = the tower's name, owner) friendly to a post of `kpOwner` with that
     * access list and trusted entries? Its owner is the post's owner or on its access list; or it is a TOWER's zone the post
     * trusts by its ICAO code bound to that tower's owner (a command post's aerodrome zone is never trusted by name).
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
        return apt.tower && trustedTower(trusted, apt.name, o);
    }

    /**
     * May a tower give its code to an aircraft of `vehOwner` on its own (the arrival on entering the zone, the automatic
     * tower's clearance)? Its owner's, his access list's, its «коды: доверенные» list's; anyone else only by the controller.
     */
    public static boolean mayAssign(String towerOwner, Collection<String> access, Collection<String> trusted, String vehOwner) {
        if (vehOwner == null || vehOwner.length() == 0) {
            return false;
        }
        if (vehOwner.equalsIgnoreCase(towerOwner == null ? "" : towerOwner)) {
            return true;
        }
        for (Collection<String> l : java.util.Arrays.asList(access, trusted)) {
            if (l != null) {
                for (String a : l) {
                    if (a != null && a.trim().equalsIgnoreCase(vehOwner)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * May a tower's code replace what the transponder squawks? Never 7500 / 7600 / 7700 (the crew's emergency), never a running
     * «ключ дня» (on, the code from today's card: the air defence's friend).
     */
    public static boolean mayReplace(int code, boolean dayKeyRunning) {
        return code != XpdrMath.HIJACK && !XpdrMath.special(code) && !dayKeyRunning;
    }

    /** Did the tower of this aerodrome zone give this code to the aircraft being classed? (the caller knows which aircraft) */
    public interface Issued {
        boolean to(ZoneMath.Zone apt, int code);
    }

    /** Inside the airport's zone (its band) or within NEAR of its outline. */
    public static boolean nearAirport(ZoneMath.Zone apt, double x, double y, double z, boolean altKnown) {
        return ZoneMath.contains(apt, x, y, z, altKnown) || ZoneMath.altIn(apt, y, altKnown) && ZoneMath.distance2D(apt, x, z) <= NEAR;
    }

    /**
     * The friendly airport whose code the flight squawks, in / near which it is - null none (then the usual classes).
     * `zones`: every zone known (the aerodromes are picked out). A tower's zone counts only when `issued` says that tower
     * gave this code to this aircraft (null: none did); a command post's aerodrome zone (its owner's / a friend's own
     * range, nobody gives it out) counts by the range alone.
     */
    public static ZoneMath.Zone friendOf(List<ZoneMath.Zone> zones, int code, double x, double y, double z, boolean altKnown, String kpOwner,
            Collection<String> access, Collection<String> trusted, Issued issued) {
        if (zones == null || !XpdrMath.valid(code)) {
            return null;
        }
        for (ZoneMath.Zone a : zones) {
            if (ZoneMath.aerodromeCode(a, code) && friendly(a, kpOwner, access, trusted) && nearAirport(a, x, y, z, altKnown)
                    && (!a.tower || issued != null && issued.to(a, code))) {
                return a;
            }
        }
        return null;
    }

    /**
     * Audit review 2026-10-22: the post's own aerodrome zone (a command post's, not a tower's) whose range the code is in and
     * the flight is inside - null none. The range is no secret (every client got the zones with their codes - no longer,
     * AirDefenseSVA.publicZone - and the default is easy to guess), so the caller takes it as an airport's code (RoeMath.classify's `airportCode`): a friend only while it is no
     * violator and no tower handed it over. Before, it was the post's own code - a stranger dialling it by hand in a
     * forbidden zone was a friend, and a post-less launcher took its owner's TOWERS' ranges so too (past the bug 6 check
     * that a tower's code counts only when that tower gave it): the towers' zones are skipped here.
     */
    public static ZoneMath.Zone postRange(List<ZoneMath.Zone> zones, int code, double x, double y, double z, boolean altKnown) {
        if (zones == null || !XpdrMath.valid(code)) {
            return null;
        }
        for (ZoneMath.Zone a : zones) {
            if (!a.tower && ZoneMath.aerodromeCode(a, code) && ZoneMath.contains(a, x, y, z, altKnown)) {
                return a;
            }
        }
        return null;
    }

    /** A tower zone's tower key («dim:x:y:z» - the zone carries the tower's block and dimension). */
    public static String towerKey(ZoneMath.Zone apt) {
        return apt.dim + ":" + apt.kx + ":" + apt.ky + ":" + apt.kz;
    }
}
