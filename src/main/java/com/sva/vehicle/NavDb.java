package com.sva.vehicle;

import java.util.ArrayList;
import java.util.List;

import com.sva.airdefense.ZoneMath;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Liner wave 5 (plan-next §7, mock-up veh_liner_14 «ДАННЫЕ»): the MCDU's navigation database - airports (each with its
 * runways) and waypoints, as pure data so the dedicated server's self-test builds and checks it. It is never stored
 * whole: the server builds it on demand (block.NavDataSVA, cached a few seconds) from what the world already has -
 * <ul>
 * <li>every «Курсо-глиссадный маяк» (IlsDataSVA): a beacon is a runway - its threshold, the landing course (the runway's
 * designator «27» = course / 10), the touchdown elevation and the ILS itself; beacons with the same ident within
 * SAME_AIRPORT blocks form one airport (two opposite beacons give the runway's length; one alone: LOC_DIST, the
 * localizer's antenna beyond the threshold - about the runway's end);</li>
 * <li>the air-defence «аэродром» zones (AirDefenseDataSVA): the zone's name as the ident, its centre, its course as the
 * runway, its long side as the length;</li>
 * <li>the pilots' own airports and waypoints (stored in the world data from the ДАННЫЕ pages);</li>
 * <li>the vehicle map's «База» point as the waypoint «БАЗА».</li>
 * </ul>
 * Idents: 2..7 letters / digits, Latin or Cyrillic, upper case (cleanIdent). Coordinates are world X / Z (the decision of
 * plan-next §7: no latitude / longitude), elevations Y (= metres).
 */
public final class NavDb {

    /** Where an entry came from. */
    public static final int SRC_ILS = 0, SRC_ZONE = 1, SRC_PILOT = 2, SRC_BASE = 3;
    /** Lang key part per source (sva.mcdu.src.KEY). */
    public static final String[] SRC_KEYS = {"ils", "zone", "pilot", "base"};
    /** Beacons with one ident this close (blocks) are one airport. */
    public static final double SAME_AIRPORT = 3000;
    /** A runway's length when nothing tells it (blocks): the localizer antenna's distance beyond the threshold. */
    public static final int DEFAULT_LEN = (int) LinerIls.LOC_DIST;
    /** The ident's length limits. */
    public static final int ID_MIN = 2, ID_MAX = 7;

    /** One runway: its designator ("27"), threshold, elevation, landing course, length, the ILS on it (key "" none). */
    public static final class Rwy {
        public String id = "";
        public double x, z;
        public int elev = 64, hdg, len = DEFAULT_LEN;
        public String ilsKey = "", ilsIdent = "";
        public int freq;

        public Rwy copy() {
            Rwy r = new Rwy();
            r.id = id;
            r.x = x;
            r.z = z;
            r.elev = elev;
            r.hdg = hdg;
            r.len = len;
            r.ilsKey = ilsKey;
            r.ilsIdent = ilsIdent;
            r.freq = freq;
            return r;
        }
    }

    /** An airport: ident, source, reference point (the first runway's threshold or the zone's centre), elevation, runways. */
    public static final class Airport {
        public String ident = "";
        public int src = SRC_PILOT, dim;
        public double x, z;
        public int elev = 64;
        public final List<Rwy> rwys = new ArrayList<Rwy>();

        /** The runway by designator ("27"), the first one for "" / unknown; null without runways. */
        public Rwy rwy(String id) {
            for (Rwy r : rwys) {
                if (r.id.equals(id)) {
                    return r;
                }
            }
            return rwys.isEmpty() ? null : rwys.get(0);
        }

        public Airport copy() {
            Airport a = new Airport();
            a.ident = ident;
            a.src = src;
            a.dim = dim;
            a.x = x;
            a.z = z;
            a.elev = elev;
            for (Rwy r : rwys) {
                a.rwys.add(r.copy());
            }
            return a;
        }

        /** The pilots' airport saved (world data): ident, position, elevation, one runway (heading, length). */
        public void write(NBTTagCompound t) {
            t.setString("Id", ident);
            t.setInteger("Dim", dim);
            t.setDouble("X", x);
            t.setDouble("Z", z);
            t.setInteger("Elev", elev);
            Rwy r = rwy("");
            t.setInteger("Hdg", r == null ? 0 : r.hdg);
            t.setInteger("Len", r == null ? DEFAULT_LEN : r.len);
        }

        public static Airport read(NBTTagCompound t) {
            String id = cleanIdent(t.getString("Id"));
            if (id.length() == 0) {
                return null;
            }
            return pilot(id, t.getInteger("Dim"), t.getDouble("X"), t.getDouble("Z"), t.getInteger("Elev"), t.getInteger("Hdg"), t.getInteger("Len"));
        }
    }

    /** A waypoint: ident, position, source. */
    public static final class Wpt {
        public String ident = "";
        public int src = SRC_PILOT, dim;
        public double x, z;

        public void write(NBTTagCompound t) {
            t.setString("Id", ident);
            t.setInteger("Dim", dim);
            t.setDouble("X", x);
            t.setDouble("Z", z);
        }

        public static Wpt read(NBTTagCompound t) {
            String id = cleanIdent(t.getString("Id"));
            if (id.length() == 0) {
                return null;
            }
            return wpt(id, SRC_PILOT, t.getInteger("Dim"), t.getDouble("X"), t.getDouble("Z"));
        }
    }

    public final List<Airport> airports = new ArrayList<Airport>();
    public final List<Wpt> wpts = new ArrayList<Wpt>();
    /** Every ILS station of the dimension (RAD NAV picks by ident / frequency). */
    public final List<LinerIls.Station> ils = new ArrayList<LinerIls.Station>();

    public Airport airport(String ident) {
        for (Airport a : airports) {
            if (a.ident.equals(ident)) {
                return a;
            }
        }
        return null;
    }

    public Wpt wpt(String ident) {
        for (Wpt w : wpts) {
            if (w.ident.equals(ident)) {
                return w;
            }
        }
        return null;
    }

    /** An ILS station by its ident or its frequency (hundredths of MHz), the nearest of several to (x, z); null none. */
    public LinerIls.Station ils(String ident, int freq, double x, double z) {
        LinerIls.Station best = null;
        double bd = Double.MAX_VALUE;
        for (LinerIls.Station s : ils) {
            if (ident != null && ident.length() > 0 ? !ident.equals(s.ident) : s.freq != freq) {
                continue;
            }
            double d = sq(s.tx() - x) + sq(s.tz() - z);
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- building

    public static Airport pilot(String ident, int dim, double x, double z, int elev, int hdg, int len) {
        Airport a = new Airport();
        a.ident = ident;
        a.src = SRC_PILOT;
        a.dim = dim;
        a.x = x;
        a.z = z;
        a.elev = elev;
        Rwy r = new Rwy();
        r.id = rwyId(hdg);
        r.x = x;
        r.z = z;
        r.elev = elev;
        r.hdg = ((hdg % 360) + 360) % 360;
        r.len = len > 0 ? len : DEFAULT_LEN;
        a.rwys.add(r);
        return a;
    }

    public static Wpt wpt(String ident, int src, int dim, double x, double z) {
        Wpt w = new Wpt();
        w.ident = ident;
        w.src = src;
        w.dim = dim;
        w.x = x;
        w.z = z;
        return w;
    }

    /**
     * The database of one dimension: the ILS stations (airports from beacons), the air-defence zones («аэродром» ones),
     * the pilots' airports and waypoints and the «База» point (null: none). Pilot entries win over automatic ones with the
     * same ident; the lists come sorted by ident.
     */
    public static NavDb build(int dim, List<LinerIls.Station> stations, List<ZoneMath.Zone> zones, List<Airport> pilotApts, List<Wpt> pilotWpts,
            int[] base) {
        NavDb db = new NavDb();
        if (pilotApts != null) {
            for (Airport a : pilotApts) {
                if (a.dim == dim && db.airport(a.ident) == null) {
                    db.airports.add(a.copy());
                }
            }
        }
        if (stations != null) {
            for (LinerIls.Station s : stations) {
                if (s.dim != dim) {
                    continue;
                }
                db.ils.add(s);
                String id = cleanIdent(s.ident);
                if (id.length() == 0) {
                    continue;
                }
                Airport a = db.airport(id);
                if (a != null && a.src == SRC_PILOT) {
                    // a pilot's airport of that name: the beacon's runway joins it when it is near
                    if (sq(a.x - s.tx()) + sq(a.z - s.tz()) > SAME_AIRPORT * SAME_AIRPORT) {
                        continue;
                    }
                } else if (a != null && sq(a.x - s.tx()) + sq(a.z - s.tz()) > SAME_AIRPORT * SAME_AIRPORT) {
                    continue;                                       // the same ident far away: the first one keeps it
                }
                if (a == null) {
                    a = new Airport();
                    a.ident = id;
                    a.src = SRC_ILS;
                    a.dim = dim;
                    a.x = s.tx();
                    a.z = s.tz();
                    a.elev = (int) Math.round(s.elev);
                    db.airports.add(a);
                }
                Rwy r = new Rwy();
                r.id = rwyId(s.course);
                r.x = s.tx();
                r.z = s.tz();
                r.elev = (int) Math.round(s.elev);
                r.hdg = (int) Math.round(s.course) % 360;
                r.ilsKey = s.key();
                r.ilsIdent = s.ident;
                r.freq = s.freq;
                // two opposite thresholds: the runway's length is the distance between them
                for (Rwy o : a.rwys) {
                    double diff = Math.abs(CruiseMath.wrap(o.hdg - r.hdg));
                    if (diff > 170) {
                        int len = (int) Math.round(Math.sqrt(sq(o.x - r.x) + sq(o.z - r.z)));
                        if (len > 20) {
                            o.len = len;
                            r.len = len;
                        }
                    }
                }
                a.rwys.add(r);
            }
        }
        if (zones != null) {
            int n = 0;
            for (ZoneMath.Zone zn : zones) {
                if (zn.type != ZoneMath.AERODROME || zn.dim != dim) {
                    continue;
                }
                n++;
                String id = cleanIdent(zn.name);
                if (id.length() < ID_MIN) {
                    id = "АД" + (n < 10 ? "0" : "") + n;
                }
                if (db.airport(id) != null) {
                    continue;
                }
                Airport a = new Airport();
                a.ident = id;
                a.src = SRC_ZONE;
                a.dim = dim;
                boolean circle = zn.shape == ZoneMath.CIRCLE;
                a.x = circle ? zn.cx + 0.5 : (zn.x0 + zn.x1) / 2.0 + 0.5;
                a.z = circle ? zn.cz + 0.5 : (zn.z0 + zn.z1) / 2.0 + 0.5;
                a.elev = Math.max(1, zn.lo > 0 ? zn.lo : 64);
                Rwy r = new Rwy();
                r.hdg = ((zn.course % 360) + 360) % 360;
                r.id = rwyId(r.hdg);
                r.x = a.x;
                r.z = a.z;
                r.elev = a.elev;
                r.len = circle ? Math.max(DEFAULT_LEN, zn.r * 2) : Math.max(DEFAULT_LEN, Math.max(Math.abs(zn.x1 - zn.x0), Math.abs(zn.z1 - zn.z0)));
                a.rwys.add(r);
                db.airports.add(a);
            }
        }
        if (pilotWpts != null) {
            for (Wpt w : pilotWpts) {
                if (w.dim == dim && db.wpt(w.ident) == null) {
                    db.wpts.add(w);
                }
            }
        }
        if (base != null && db.wpt(BASE) == null) {
            db.wpts.add(wpt(BASE, SRC_BASE, dim, base[0] + 0.5, base[1] + 0.5));
        }
        java.util.Comparator<Airport> ca = new java.util.Comparator<Airport>() {
            @Override
            public int compare(Airport p, Airport q) {
                return p.ident.compareTo(q.ident);
            }
        };
        java.util.Collections.sort(db.airports, ca);
        java.util.Collections.sort(db.wpts, new java.util.Comparator<Wpt>() {
            @Override
            public int compare(Wpt p, Wpt q) {
                return p.ident.compareTo(q.ident);
            }
        });
        return db;
    }

    /** The «База» waypoint's ident. */
    public static final String BASE = "БАЗА";

    /** A runway designator from a course: 2 digits, 01..36 ("36" for north). */
    public static String rwyId(double course) {
        int n = (int) Math.round(LinerIls.wrap360(course) / 10.0);
        if (n == 0) {
            n = 36;
        }
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    /** An ident: letters / digits (Latin, Cyrillic), upper case, at most ID_MAX; shorter than ID_MIN - "". */
    public static String cleanIdent(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < ID_MAX; i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c >= 'А' && c <= 'Я' || c == 'Ё') {
                b.append(c == 'Ё' ? 'Е' : c);
            }
        }
        return b.length() >= ID_MIN ? b.toString() : "";
    }

    static double sq(double v) {
        return v * v;
    }
}
