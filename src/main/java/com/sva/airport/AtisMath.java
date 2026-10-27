package com.sva.airport;

import java.util.ArrayList;
import java.util.List;

import com.sva.vehicle.LinerMcdu;

/**
 * Airport wave B2 (plan-next §9, item 30): ATIS - the airport's recorded information, made by the tower from what its
 * airport knows (B1's weather station, RVR sensors, ILS monitor) - pure (no Minecraft classes; the self-test checks the
 * letter rules and the text in both languages).
 * <p>
 * <b>The information letter</b> (АЛЬФА, БРАВО ... ЗУЛУ, then АЛЬФА again) moves on only when something a crew must know
 * changed beyond THRESHOLDS (changed()): the runway in use, the approach (its type and category), the wind's direction
 * (WIND_DIR deg, only above WIND_CALM km/h) or speed (WIND_SPD km/h), gusts coming / going, the visibility crossing one of
 * VIS_BANDS, the RVR's needed category, the QNH (QNH_STEP hPa), the temperature (TEMP_STEP deg) or the remarks. The noise of
 * the weather inside those steps keeps the letter.
 * <p>
 * <b>The text</b> (text()): lines of lang keys with arguments («sva.atis.*»), resolved by the reader's language (the MCDU
 * through its own resolver, the chat as translations): «UUEE ИНФОРМАЦИЯ БРАВО, 12:40 / ПОЛОСА 27L / ЗАХОД ILS CAT III /
 * ВЕТЕР 240 ГРАД 18 КМ/Ч ПОРЫВЫ 30 / ВИДИМОСТЬ 1500 М, RVR 27L 550 / ТЕМПЕРАТУРА 12 / QNH 1008 / ... / СООБЩИТЕ О ПОЛУЧЕНИИ
 * ИНФОРМАЦИИ БРАВО». Units as the cockpit's: km/h, m-eq (the B1 scale), hPa; no degree sign (the font has none).
 */
public final class AtisMath {

    /** Letters: 26 (ICAO's alphabet; the words are lang keys sva.atis.letter.N). */
    public static final int LETTERS = 26;
    /** Thresholds of a new letter. */
    public static final int WIND_DIR = 30, WIND_SPD = 10, WIND_CALM = 6, GUST_MIN = 10, QNH_STEP = 1, TEMP_STEP = 3;
    /** Visibility bands (m-eq): crossing one of them is a new letter. */
    public static final int[] VIS_BANDS = {5000, 3000, 1500, 800, 550, 300, 150};
    /** Approach kinds. */
    public static final int A_VISUAL = 0, A_LOC = 1, A_ILS = 2;
    /** Remarks (bits): ILS out of service / not calibrated, low visibility procedures, thunderstorm, taxi lights «follow the greens», runway lights off. */
    public static final int RMK_ILS_OFF = 1, RMK_UNCAL = 2, RMK_LVP = 4, RMK_THUNDER = 8, RMK_FOLLOW = 16, RMK_LIGHTS_OFF = 32, RMK_NO_WX = 64,
            /**
             * Embassy airfield (docs/plan-embassy-airfield-addon.md §3.2, §4): ПОСАДКА В КУПОЛЕ ЗАПРЕЩЕНА (every embassy), ЛАЙНЕР НЕ
             * ПРИНИМАЕТСЯ (SHORT unless airport.embassyLinerShort), the liner's autobrake LO or higher (FULL: 181 from the threshold).
             */
            RMK_EMB_DOME = 128, RMK_EMB_SHORT = 256, RMK_EMB_BRAKE = 512;
    public static final String[] RMK_KEYS = {"ilsOff", "uncal", "lvp", "thunder", "follow", "lightsOff", "noWx", "embDome", "embShort", "embBrake"};

    private AtisMath() {
    }

    /** Embassy airfield: an embassy tower's own remarks - the dome always, no liner on SHORT (unless allowed), the autobrake on FULL. */
    public static int embassyRemarks(boolean full, boolean shortRunway, boolean linerShortAllowed) {
        return RMK_EMB_DOME | (shortRunway && !linerShortAllowed ? RMK_EMB_SHORT : 0) | (full ? RMK_EMB_BRAKE : 0);
    }

    /** What the ATIS reports (an observation): the runway, the approach, the weather, the remarks. */
    public static final class Obs {
        public String rwy = "";
        public int appr = A_VISUAL, cat;
        /** Wind from (deg; -1: no weather station), mean / gust speed (km/h). */
        public int windFrom = -1, windKmh, gustKmh;
        /** Visibility (m-eq; -1 unknown), RVR TDZ / MID / END (m-eq; -1 none), temperature (deg C), QNH (hPa; 0 unknown). */
        public int vis = -1, temp, qnh;
        public int[] rvr = {-1, -1, -1};
        public int rmk;

        public Obs copy() {
            Obs o = new Obs();
            o.rwy = rwy;
            o.appr = appr;
            o.cat = cat;
            o.windFrom = windFrom;
            o.windKmh = windKmh;
            o.gustKmh = gustKmh;
            o.vis = vis;
            o.temp = temp;
            o.qnh = qnh;
            o.rvr = rvr.clone();
            o.rmk = rmk;
            return o;
        }

        /** The packed form (the tower's save, the clients' copy). */
        public int[] pack() {
            return new int[] {appr, cat, windFrom, windKmh, gustKmh, vis, temp, qnh, rvr[0], rvr[1], rvr[2], rmk};
        }

        public static Obs unpack(String rwy, int[] p) {
            Obs o = new Obs();
            o.rwy = rwy == null ? "" : rwy;
            if (p == null || p.length < 12) {
                return o;
            }
            o.appr = p[0];
            o.cat = p[1];
            o.windFrom = p[2];
            o.windKmh = p[3];
            o.gustKmh = p[4];
            o.vis = p[5];
            o.temp = p[6];
            o.qnh = p[7];
            o.rvr = new int[] {p[8], p[9], p[10]};
            o.rmk = p[11];
            return o;
        }
    }

    /** The next letter (Z -> A). */
    public static int next(int letter) {
        return ((letter % LETTERS) + LETTERS + 1) % LETTERS;
    }

    /** The visibility's band index (0 above the first band). */
    public static int visBand(int vis) {
        if (vis < 0) {
            return -1;
        }
        int b = 0;
        for (int v : VIS_BANDS) {
            if (vis < v) {
                b++;
            }
        }
        return b;
    }

    /** The category the RVR needs (0: no RVR reported). */
    public static int rvrCat(int[] rvr) {
        int min = Integer.MAX_VALUE;
        for (int r : rvr) {
            if (r >= 0) {
                min = Math.min(min, r);
            }
        }
        return min == Integer.MAX_VALUE ? 0 : RvrMath.requiredCat(min);
    }

    static int angle(int a, int b) {
        int d = Math.abs(((a - b) % 360 + 540) % 360 - 180);
        return d;
    }

    /** Is `now` a new information against the one published (`was`; null: none yet)? */
    public static boolean changed(Obs was, Obs now) {
        if (was == null) {
            return true;
        }
        if (!was.rwy.equals(now.rwy) || was.appr != now.appr || was.cat != now.cat || was.rmk != now.rmk) {
            return true;
        }
        if ((was.windFrom < 0) != (now.windFrom < 0)) {
            return true;
        }
        if (now.windFrom >= 0) {
            boolean calm = was.windKmh < WIND_CALM && now.windKmh < WIND_CALM;
            if (!calm && angle(was.windFrom, now.windFrom) >= WIND_DIR || Math.abs(was.windKmh - now.windKmh) >= WIND_SPD) {
                return true;
            }
            boolean gw = was.gustKmh - was.windKmh >= GUST_MIN, gn = now.gustKmh - now.windKmh >= GUST_MIN;
            if (gw != gn) {
                return true;
            }
            if (Math.abs(was.qnh - now.qnh) >= QNH_STEP || Math.abs(was.temp - now.temp) >= TEMP_STEP) {
                return true;
            }
        }
        return visBand(was.vis) != visBand(now.vis) || rvrCat(was.rvr) != rvrCat(now.rvr);
    }

    // ---------------------------------------------------------------- the text

    /** One line: a lang key and its arguments (an argument "#key" is itself translated). */
    public static final class Line {
        public final String key;
        public final String[] args;

        Line(String key, String... args) {
            this.key = key;
            this.args = args;
        }
    }

    /** The hour:minute of a world time (the tower's clock: 6000 = 12:00). */
    public static String clock(long worldTime) {
        long t = ((worldTime + 6000) % 24000 + 24000) % 24000;
        int m = (int) (t * 1440 / 24000);
        return (m / 60 < 10 ? "0" : "") + m / 60 + ":" + (m % 60 < 10 ? "0" : "") + m % 60;
    }

    /** The approach's words (a lang key with its argument). */
    static Line approach(Obs o) {
        if (o.appr == A_ILS) {
            return new Line("sva.atis.apprIls", IlsCat.catText(o.cat));
        }
        if (o.appr == A_LOC) {
            return new Line("sva.atis.apprLoc");
        }
        return new Line("sva.atis.apprVisual");
    }

    /** The whole information (see the class comment). */
    public static List<Line> text(String icao, String name, int letter, long worldTime, Obs o) {
        List<Line> l = new ArrayList<Line>();
        String let = "#sva.atis.letter." + (((letter % LETTERS) + LETTERS) % LETTERS);
        l.add(new Line("sva.atis.head", name == null || name.length() == 0 ? icao : name, let, clock(worldTime)));
        if (o.rwy.length() > 0) {
            l.add(new Line("sva.atis.rwy", o.rwy));
            l.add(approach(o));
        } else {
            l.add(new Line("sva.atis.noRwy"));
        }
        if (o.windFrom < 0) {
            l.add(new Line("sva.atis.windNone"));
        } else if (o.windKmh < WIND_CALM) {
            l.add(new Line("sva.atis.calm"));
        } else if (o.gustKmh - o.windKmh >= GUST_MIN) {
            l.add(new Line("sva.atis.windGust", TowerMath.three(o.windFrom), String.valueOf(o.windKmh), String.valueOf(o.gustKmh)));
        } else {
            l.add(new Line("sva.atis.wind", TowerMath.three(o.windFrom), String.valueOf(o.windKmh)));
        }
        if (o.vis >= 0) {
            l.add(new Line(o.vis >= 10000 ? "sva.atis.visFar" : "sva.atis.vis", String.valueOf(o.vis)));
        }
        if (o.rvr[0] >= 0 && (o.rvr[0] < RvrMath.RVR_MAX || o.vis >= 0 && o.vis < 1500)) {
            StringBuilder b = new StringBuilder(RvrMath.text(o.rvr[0]));
            for (int i = 1; i < 3; i++) {
                if (o.rvr[i] >= 0) {
                    b.append(" / ").append(RvrMath.text(o.rvr[i]));
                }
            }
            l.add(new Line("sva.atis.rvr", o.rwy, b.toString()));
        }
        if (o.windFrom >= 0) {
            l.add(new Line("sva.atis.temp", String.valueOf(o.temp)));
            l.add(new Line("sva.atis.qnh", String.valueOf(o.qnh)));
        }
        for (int i = 0; i < RMK_KEYS.length; i++) {
            if ((o.rmk & (1 << i)) != 0) {
                l.add(new Line("sva.atis.rmk." + RMK_KEYS[i]));
            }
        }
        l.add(new Line("sva.atis.end", let));
        return l;
    }

    /** The lines through a resolver (an argument "#key" translated first). */
    public static List<String> render(List<Line> lines, LinerMcdu.Resolver r) {
        List<String> out = new ArrayList<String>();
        for (Line ln : lines) {
            String[] a = new String[ln.args.length];
            for (int i = 0; i < a.length; i++) {
                a[i] = ln.args[i].startsWith("#") ? r.tr(ln.args[i].substring(1), new String[0]) : ln.args[i];
            }
            out.add(r.tr(ln.key, a));
        }
        return out;
    }

    /** The packed lines for a packet / a save: key \u0001 arg \u0001 arg ... \u0002 key ... */
    public static String pack(List<Line> lines) {
        StringBuilder b = new StringBuilder();
        for (Line ln : lines) {
            if (b.length() > 0) {
                b.append('\u0002');
            }
            b.append(ln.key);
            for (String a : ln.args) {
                b.append('\u0001').append(a.replace('\u0001', ' ').replace('\u0002', ' '));
            }
        }
        return b.toString();
    }

    public static List<Line> unpack(String s) {
        List<Line> out = new ArrayList<Line>();
        if (s == null || s.length() == 0) {
            return out;
        }
        for (String e : s.split("\u0002")) {
            String[] f = e.split("\u0001", -1);
            String[] a = new String[f.length - 1];
            System.arraycopy(f, 1, a, 0, a.length);
            out.add(new Line(f[0], a));
        }
        return out;
    }

    /** Words wrapped into lines of at most `cols` characters (the MCDU's 24, the chat's longer). */
    public static List<String> wrap(String s, int cols) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.length() == 0) {
                continue;
            }
            while (w.length() > cols) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add(w.substring(0, cols));
                w = w.substring(cols);
            }
            if (cur.length() > 0 && cur.length() + 1 + w.length() > cols) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append(' ');
            }
            cur.append(w);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
