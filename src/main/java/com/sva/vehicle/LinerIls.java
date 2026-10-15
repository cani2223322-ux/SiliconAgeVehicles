package com.sva.vehicle;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Liner wave 4 (plan-next §7, mock-up veh_liner_11 sheet 3): the ILS of the «Курсо-глиссадный маяк» block - pure geometry,
 * the same code on the server (the AFS' LOC / G/S capture, the tuning) and on every client (the PFD's diamonds, the ND's
 * ROSE ILS deviation bar), so nothing per tick has to be synced but the tuned station.
 * <p>
 * A STATION is the threshold point T (the block's centre moved `offset` blocks right of the landing direction - a beacon
 * placed beside the strip; 0 for one set flush into the surface), the touchdown zone's elevation E (the surface's top),
 * the landing COURSE (compass degrees: 0 north = -z, 90 east = +x - the ND's convention), the glide slope ANGLE, the
 * ident (3-4 letters) and the frequency (108.10 .. 111.95 MHz, hundredths). The localizer's antenna sits LOC_DIST blocks
 * beyond T along the course (the far end of a typical strip), the glide slope's at T itself: the beam reaches the surface
 * at the threshold, the flare carries the touchdown ~40 blocks in (the A320's touchdown zone, scaled).
 * <p>
 * Deviations: LOC in degrees seen from its antenna (1 dot = LOC_DOT deg, full scale 2 dots), G/S in degrees seen from its
 * antenna (1 dot = GS_DOT deg); positive = the aircraft right of the centre line / above the glide path. The capture
 * cones: the LOC within LOC_COVER deg of the course seen from its antenna and nearer than the range, inbound; the G/S
 * within GS_COVER_LO..GS_COVER_HI x the angle (no false upper lobes here - the cone is the whole beam).
 */
public final class LinerIls {

    /** The localizer's antenna beyond the threshold (blocks), its dot (deg), its coverage (deg either side of the course). */
    public static final double LOC_DIST = 200, LOC_DOT = 0.8, LOC_COVER = 35;
    /** The glide slope's dot (deg), its coverage (x the angle, low / high). */
    public static final double GS_DOT = 0.35, GS_COVER_LO = 0.3, GS_COVER_HI = 1.75;
    /** The LOC capture's allowance for the roll-in (ticks of drift before the bank is there). */
    public static final double ROLL_IN = 20;
    /** The GUI's limits: frequency (hundredths of MHz), angle (deg), the centre line's offset (blocks), the ident's length. */
    public static final int FREQ_MIN = 10810, FREQ_MAX = 11195, FREQ_DEFAULT = 11030, OFFSET_MAX = 20;
    public static final double ANGLE_MIN = 2.0, ANGLE_MAX = 5.0, ANGLE_DEFAULT = 3.0;

    /** Airport wave B1: the station kinds and a FULL station's status bits (the monitor's flags). */
    public static final int KIND_COMPACT = 0, KIND_FULL = 1;
    public static final int F_LOC_FAIL = 1, F_GS_FAIL = 2, F_UNCAL = 4, F_NO_GS = 8, F_OFF = 16, F_NO_POWER = 32;
    /**
     * Airport wave B1: the category of the legacy single beacons (ConfigSVA airport.legacyIlsCat, 3 by default: an old
     * world's beacon keeps working exactly as in wave 4, autoland included; 1 makes them CAT I «compact» stations).
     */
    public static int legacyCat = 3;

    private LinerIls() {
    }

    static double fin(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? 0 : v;
    }

    /** One beacon. */
    public static final class Station {
        /** The block, its dimension. */
        public int dim, bx, by, bz;
        /** The touchdown elevation (Y of the surface's top), the course (deg), the glide angle (deg), the offset (blocks, + right). */
        public double elev, course, angle = ANGLE_DEFAULT;
        public int offset;
        public String ident = "";
        public int freq = FREQ_DEFAULT;
        /** A client's mirror: the threshold point as synced (NaN: from the block and the offset); airport wave B1: a FULL station's own. */
        public double fx = Double.NaN, fz = Double.NaN;
        /**
         * Airport wave B1 (com.sva.airport): COMPACT (the wave-4 single beacon, legacy: its own simple beam, its category
         * airport.legacyIlsCat) or FULL (a LOC array + a G/S mast paired over a runway: the real beam of airport.IlsBeam).
         */
        public int kind = KIND_COMPACT;
        /** FULL: the localizer array's centre (x, z), the glide slope mast's foot (x, z) and the ground there (Y). */
        public double lx, lz, gx, gz, gy;
        /** FULL: the category (0 off .. 3), the status (F_*), the beam's bends (deg amplitude), their phase (0..999). */
        public int cat = 3, flags, phase;
        public double bendLoc, bendGs;
        /** FULL: the runway's designator ("27L"), the airport's ident (ICAO placeholder), the runway's length (blocks). */
        public String rwy = "", apt = "";
        public int rwyLen;
        /** FULL: the markers' distances before the threshold (blocks; -1 none): OM, MM, IM. */
        public final int[] markers = {-1, -1, -1};
        /** FULL: the flight inspection (airport.FlightCheck: its state and result word). */
        public int calib;

        /** The threshold point's x / z (the block's centre + the offset to the right of the landing direction). */
        public double tx() {
            return !Double.isNaN(fx) ? fx : bx + 0.5 + Math.cos(Math.toRadians(course)) * offset;
        }

        public double tz() {
            return !Double.isNaN(fz) ? fz : bz + 0.5 + Math.sin(Math.toRadians(course)) * offset;
        }

        /** The world data's key. */
        public String key() {
            return dim + ":" + bx + ":" + by + ":" + bz;
        }

        public Station copy() {
            Station s = new Station();
            s.dim = dim;
            s.bx = bx;
            s.by = by;
            s.bz = bz;
            s.elev = elev;
            s.course = course;
            s.angle = angle;
            s.offset = offset;
            s.ident = ident;
            s.freq = freq;
            s.fx = fx;
            s.fz = fz;
            s.kind = kind;
            s.lx = lx;
            s.lz = lz;
            s.gx = gx;
            s.gz = gz;
            s.gy = gy;
            s.cat = cat;
            s.flags = flags;
            s.phase = phase;
            s.bendLoc = bendLoc;
            s.bendGs = bendGs;
            s.rwy = rwy;
            s.apt = apt;
            s.rwyLen = rwyLen;
            System.arraycopy(markers, 0, s.markers, 0, 3);
            s.calib = calib;
            return s;
        }

        public void write(NBTTagCompound t) {
            t.setInteger("Dim", dim);
            t.setInteger("X", bx);
            t.setInteger("Y", by);
            t.setInteger("Z", bz);
            t.setDouble("Elev", elev);
            t.setDouble("Course", course);
            t.setDouble("Angle", angle);
            t.setInteger("Offset", offset);
            t.setString("Ident", ident);
            t.setInteger("Freq", freq);
            if (kind == KIND_FULL) {
                // airport wave B1: a FULL station's own fields (a COMPACT one writes exactly what wave 4 wrote)
                t.setInteger("Kind", kind);
                t.setDouble("Tx", tx());
                t.setDouble("Tz", tz());
                t.setDouble("Lx", lx);
                t.setDouble("Lz", lz);
                t.setDouble("Gx", gx);
                t.setDouble("Gz", gz);
                t.setDouble("Gy", gy);
                t.setInteger("Cat", cat);
                t.setInteger("Flags", flags);
                t.setInteger("Phase", phase);
                t.setDouble("BendL", bendLoc);
                t.setDouble("BendG", bendGs);
                t.setString("Rwy", rwy);
                t.setString("Apt", apt);
                t.setInteger("RwyLen", rwyLen);
                t.setIntArray("Mk", markers.clone());
                t.setInteger("Calib", calib);
            }
        }

        public void read(NBTTagCompound t) {
            dim = t.getInteger("Dim");
            bx = t.getInteger("X");
            by = t.getInteger("Y");
            bz = t.getInteger("Z");
            elev = t.getDouble("Elev");
            course = wrap360(t.getDouble("Course"));
            angle = t.hasKey("Angle") ? clampAngle(t.getDouble("Angle")) : ANGLE_DEFAULT;
            offset = Math.max(-OFFSET_MAX, Math.min(OFFSET_MAX, t.getInteger("Offset")));
            ident = cleanIdent(t.getString("Ident"));
            freq = t.hasKey("Freq") ? clampFreq(t.getInteger("Freq")) : FREQ_DEFAULT;
            kind = t.getInteger("Kind") == KIND_FULL ? KIND_FULL : KIND_COMPACT;
            if (kind == KIND_FULL) {
                fx = t.getDouble("Tx");
                fz = t.getDouble("Tz");
                lx = t.getDouble("Lx");
                lz = t.getDouble("Lz");
                gx = t.getDouble("Gx");
                gz = t.getDouble("Gz");
                gy = t.getDouble("Gy");
                cat = Math.max(0, Math.min(3, t.getInteger("Cat")));
                flags = t.getInteger("Flags");
                phase = Math.abs(t.getInteger("Phase")) % 1000;
                bendLoc = fin(t.getDouble("BendL"));
                bendGs = fin(t.getDouble("BendG"));
                rwy = t.getString("Rwy");
                apt = t.getString("Apt");
                rwyLen = Math.max(0, t.getInteger("RwyLen"));
                int[] mk = t.getIntArray("Mk");
                for (int i = 0; i < 3; i++) {
                    markers[i] = i < mk.length ? mk[i] : -1;
                }
                calib = t.getInteger("Calib");
            } else {
                fx = Double.NaN;
                fz = Double.NaN;
            }
        }

        /** Airport wave B1: a FULL station (a paired LOC + G/S). */
        public boolean full() {
            return kind == KIND_FULL;
        }

        /** The category the receivers see (0 off, 1..3; a COMPACT one: airport.legacyIlsCat). */
        public int category() {
            return kind == KIND_FULL ? cat : legacyCat;
        }

        /** Airport wave B1: the station transmits nothing (its monitor shut it off, no power, an antenna broken). */
        public boolean off() {
            return kind == KIND_FULL && (cat <= 0 || (flags & (F_OFF | F_NO_POWER)) != 0);
        }
    }

    // ---------------------------------------------------------------- the GUI's values

    /** 108.10 .. 111.95 MHz in 0.05 steps (hundredths). */
    public static int clampFreq(int f) {
        int c = Math.max(FREQ_MIN, Math.min(FREQ_MAX, f));
        return (c + 2) / 5 * 5;
    }

    public static double clampAngle(double a) {
        return Double.isNaN(a) ? ANGLE_DEFAULT : Math.max(ANGLE_MIN, Math.min(ANGLE_MAX, Math.round(a * 10) / 10.0));
    }

    /** The ident: 3-4 letters / digits (Latin or Cyrillic), upper case; anything else dropped; too short - "". */
    public static String cleanIdent(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 4; i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c >= 'А' && c <= 'Я' || c == 'Ё') {
                b.append(c);
            }
        }
        return b.length() >= 3 ? b.toString() : "";
    }

    /** "110.30" for 11030. */
    public static String freqText(int f) {
        return f / 100 + "." + (f % 100 < 10 ? "0" : "") + f % 100;
    }

    /** The course the beacon suggests for a player facing `yaw` (Minecraft's rotationYaw): his compass heading, whole degrees. */
    public static double suggestCourse(float yaw) {
        return Math.round(wrap360(yaw + 180.0)) % 360;
    }

    // ---------------------------------------------------------------- geometry

    /** The geometry of an aircraft at (x, y, z) against a station. */
    public static final class Dev {
        /** Along the course from the threshold (blocks; negative before it), across (+ right of the centre line). */
        public double along, cross;
        /** The LOC deviation (deg, + right), the G/S deviation (deg, + above), the beam's height here and the height over it. */
        public double loc, gs, beamY, above;
        /** Slant distance to the threshold (blocks). */
        public double dist;
        /** Inside the LOC's / G/S's coverage, inbound (before the LOC antenna, facing the landing direction's side). */
        public boolean locValid, gsValid;
        /**
         * Airport wave B1: within the beam's coverage by geometry alone (a station off still counts - it stays tuned with its
         * red flags), the station's LOC / G/S failed (the PFD's red flags), behind the localizer (the back course: reverse
         * sensing, no G/S), the glide slope lobe the aircraft is in (1 the main, 2 the reversed 6 deg one, 3 the 9 deg one; 0
         * under), the elevation angle seen from the G/S antenna (deg), the LOC's dot (deg) for this station.
         */
        public boolean inCover, locFail, gsFail, backCourse;
        public int lobe = 1;
        public double elevDeg, locDot = LOC_DOT;
    }

    public static Dev dev(Station s, double x, double y, double z, double range) {
        return dev(s, x, y, z, range, 0);
    }

    /** Airport wave B1: the geometry at world time `time` (ticks: a FULL station's beam bends oscillate with it). */
    public static Dev dev(Station s, double x, double y, double z, double range, double time) {
        if (s.kind == KIND_FULL) {
            return com.sva.airport.IlsBeam.dev(s, x, y, z, range, time);
        }
        Dev d = new Dev();
        double c = Math.toRadians(s.course);
        double dx = x - s.tx(), dz = z - s.tz();
        double fx = Math.sin(c), fz = -Math.cos(c), rx = Math.cos(c), rz = Math.sin(c);
        d.along = dx * fx + dz * fz;
        d.cross = dx * rx + dz * rz;
        d.dist = Math.sqrt(dx * dx + dz * dz + (y - s.elev) * (y - s.elev));
        double toLoc = LOC_DIST - d.along;
        d.loc = Math.toDegrees(Math.atan2(d.cross, Math.max(1e-6, toLoc)));
        double before = -d.along;
        double tan = Math.tan(Math.toRadians(s.angle));
        d.beamY = s.elev + Math.max(0, before) * tan;
        d.above = y - d.beamY;
        d.gs = before > 0.5 ? Math.toDegrees(Math.atan2(y - s.elev, before)) - s.angle : 0;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        d.locValid = toLoc > 0 && horiz <= range && Math.abs(d.loc) <= LOC_COVER;
        double elevDeg = before > 0.5 ? Math.toDegrees(Math.atan2(y - s.elev, before)) : -1;
        d.gsValid = d.locValid && before > 0.5 && Math.abs(d.loc) <= 8 && elevDeg >= GS_COVER_LO * s.angle && elevDeg <= GS_COVER_HI * s.angle;
        d.inCover = d.locValid;
        d.elevDeg = elevDeg;
        return d;
    }

    /** LOC deviation in dots (clamped to +-2.5 for drawing). */
    public static double locDots(Dev d) {
        return Math.max(-2.5, Math.min(2.5, d.loc / d.locDot));
    }

    public static double gsDots(Dev d) {
        return Math.max(-2.5, Math.min(2.5, d.gs / GS_DOT));
    }

    /**
     * The LOC capture (LOC*): valid, converging (the track points toward the centre line, or within 3 deg of the course),
     * and nearer the centre line than a turn of `radius` blocks onto it needs (+ 2): the turn ends on the beam.
     */
    public static boolean locCapture(Dev d, Station s, double track, double radius, double speed) {
        if (!d.locValid) {
            return false;
        }
        double rel = CruiseMath.wrap(track - s.course);            // + = heading right of the course
        if (Math.abs(rel) > 100) {
            return false;
        }
        boolean converging = d.cross > 0 ? rel < 3 : rel > -3;
        // + the drift during the roll-in (~ROLL_IN ticks to reach the bank)
        double need = radius * (1 - Math.cos(Math.toRadians(Math.abs(rel)))) + 2 + speed * Math.sin(Math.toRadians(Math.abs(rel))) * ROLL_IN;
        if (d.backCourse || !d.locValid) {
            return false;                                               // airport wave B1: the A320 has no back-course mode
        }
        return converging && Math.abs(d.cross) <= need || Math.abs(d.loc) <= 0.25 * d.locDot;
    }

    /** The G/S capture (G/S*): valid and the beam come down to within `band` blocks over the aircraft (or the aircraft above it). */
    public static boolean gsCapture(Dev d, double band) {
        return d.gsValid && d.above >= -band;
    }

    /**
     * Which station to tune: the hand-picked one (key, still listed), else the nearest one whose landing course lies within
     * 60 deg of the track and that the aircraft is before (inbound), within range. null: none.
     */
    public static Station pick(java.util.List<Station> near, String chosen, double x, double y, double z, double track, double range) {
        if (chosen != null && chosen.length() > 0) {
            for (Station s : near) {
                if (chosen.equals(s.key())) {
                    return s;
                }
            }
        }
        Station best = null;
        double bd = Double.MAX_VALUE;
        for (Station s : near) {
            Dev d = dev(s, x, y, z, range);
            if (!d.inCover || d.backCourse || Math.abs(CruiseMath.wrap(track - s.course)) > 60) {
                continue;
            }
            double h = Math.sqrt(sq(x - s.tx()) + sq(z - s.tz()));
            if (h < bd) {
                bd = h;
                best = s;
            }
        }
        return best;
    }

    /** The ident as two ints (16 bits a char) for the sync string, and back. */
    public static int[] packIdent(String s) {
        int[] r = new int[2];
        for (int i = 0; i < Math.min(4, s.length()); i++) {
            r[i / 2] |= (s.charAt(i) & 0xFFFF) << (i % 2 == 0 ? 0 : 16);
        }
        return r;
    }

    public static String unpackIdent(int a, int b) {
        StringBuilder sb = new StringBuilder();
        int[] w = {a, b};
        for (int i = 0; i < 4; i++) {
            int c = w[i / 2] >>> (i % 2 == 0 ? 0 : 16) & 0xFFFF;
            if (c != 0) {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    static double sq(double v) {
        return v * v;
    }

    public static double wrap360(double a) {
        double r = a % 360;
        return r < 0 ? r + 360 : r;
    }
}
