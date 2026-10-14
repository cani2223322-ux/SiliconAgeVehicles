package com.sva.vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * Liner wave 5 (plan-next §7, mock-ups veh_liner_14 MCDU, veh_liner_8 the pedestal): the SA-320's two MCDUs - each its
 * own page, scratchpad and message over the ONE shared FMGC (LinerFms), like the A320's. Pure and server-authoritative:
 * the client sends key codes (the keyboard screen, the PC keyboard), the server runs key() and sends back the screen
 * render() makes - 24 columns x 14 lines of segments (text or a lang key with its arguments, a colour, big / small), so
 * the screen the pilot sees, the 3D cockpit's MCDU faces, the preview and the self-test all read the same thing.
 * <p>
 * The A320 layout: line 0 the title, lines 1..12 the six LSK rows (a small label line over a big data line), line 13 the
 * scratchpad. Colours: cyan - modifiable, green - data, amber boxes - mandatory, magenta - constraints / active, white -
 * labels, yellow - the temporary plan. Entering: type into the scratchpad, press the line's key (LSK); CLR on an empty
 * scratchpad arms a deletion («СБР») for the next LSK. Messages («НЕВЕРНЫЙ ВВОД», «НЕТ В БАЗЕ»...) cover the scratchpad
 * until CLR or a key.
 * <p>
 * Pages: ИНИЦ (INIT A) / ИНИЦ Б (fuel), ИНС ИНИЦ, ПЛАН (F-PLN) with LAT REV / VERT REV, НАПРЯМУЮ (DIR TO), ХАР (PERF: ВЗЛЁТ,
 * НАБОР, КРЕЙСЕР, СНИЖЕНИЕ, ЗАХОД, УХОД), ТОПЛ ПРОГН, ПРОГ, РАДИОНАВ (+ the beacons' list), ДАННЫЕ (position, airports,
 * waypoints - the pilots' own are stored through Store into the world data).
 */
public final class LinerMcdu {

    // ---------------------------------------------------------------- keys

    /** LSKs: left 1..6 = 0..5, right 1..6 = 6..11. */
    public static final int K_L1 = 0, K_R1 = 6;
    public static final int K_DIR = 20, K_PROG = 21, K_PERF = 22, K_INIT = 23, K_DATA = 24, K_FPLN = 25, K_RAD = 26, K_FUEL = 27, K_SEC = 28,
            K_ATC = 29, K_MENU = 30, K_AIRPORT = 31, K_PREV = 32, K_NEXT = 33, K_UP = 34, K_DOWN = 35, K_CLR = 36, K_OVFY = 37, K_SP = 38,
            K_PLUSMINUS = 39, K_BRT = 40, K_DIM = 41,
            /** The keyboard screen opened (the server sends the screen at once). */
            K_OPEN = 42;
    /** A character typed: K_CHAR + its code. */
    public static final int K_CHAR = 1000;
    /** The scratchpad's length. */
    public static final int SP_MAX = 22;
    public static final int COLS = 24, ROWS = 14;

    // ---------------------------------------------------------------- pages

    public static final int P_INIT = 0, P_INITB = 1, P_IRS = 2, P_FPLN = 3, P_LATREV = 4, P_VERTREV = 5, P_DIR = 6, P_PERF_TO = 7, P_PERF_CLB = 8,
            P_PERF_CRZ = 9, P_PERF_DES = 10, P_PERF_APPR = 11, P_PERF_GA = 12, P_FUEL = 13, P_PROG = 14, P_RAD = 15, P_ILS_LIST = 16, P_DATA = 17,
            P_APT_LIST = 18, P_APT = 19, P_WPT_LIST = 20, P_POS = 21, P_MENU = 22, PAGES = 23;

    // ---------------------------------------------------------------- colours

    public static final int C_WHITE = 0, C_CYAN = 1, C_GREEN = 2, C_AMBER = 3, C_MAGENTA = 4, C_YELLOW = 5, C_BOX = 6;

    // ---------------------------------------------------------------- one MCDU's own state

    public static final class Side {
        public int page = P_INIT, scroll, confirm;
        public String sp = "", msg = "", selId = "", dirId = "", progTo = "";
        public boolean clr, ovfy, msgAmber;
    }

    public final Side[] sides = {new Side(), new Side()};

    /** The pilots' own airports / waypoints stored (the world data); null message key = done. */
    public interface Store {
        String putWpt(NavDb.Wpt w);

        String putApt(NavDb.Airport a);

        String delete(String ident, boolean airport);
    }

    /** What the MCDU knows of the world this moment (the server's entity; the self-test's numbers). */
    public static final class Env {
        public NavDb db = new NavDb();
        public Store store;
        public int dim;
        public double x, y = 64, z, hdg, gs;
        public boolean ground = true;
        /** The world's time (ticks) for the clock and the ETAs. */
        public long time;
        /** The INS (SystemsMath.INS_*), its seconds left, the IRS INIT's automatic confirmation (config). */
        public int insStatus = SystemsMath.INS_READY, insSec;
        public boolean autoIrs = true;
        /** Fuel on board (mB), the gross weight (kg), riders, the consumption multiplier, the acceleration height's default. */
        public double fob, gw = LinerAfs.MASS0 + 1500, burn = 1;
        public int riders, accDefault = 30;
        /** The ILS stations near (RAD NAV's list). */
        public List<LinerIls.Station> ils = new ArrayList<LinerIls.Station>();
    }

    // ---------------------------------------------------------------- the screen

    /** One segment: row, column, align (0 left from col, 1 right ending before col, 2 centred on col), colour, small, text. */
    public static final class Seg {
        public int row, col, align, color;
        public boolean small;
        public String text;
    }

    /** Turns a lang key and its arguments into the text (the client: Lang; the self-test: the lang file). */
    public interface Resolver {
        String tr(String key, String[] args);
    }

    public static final class Screen {
        public final List<Seg> segs = new ArrayList<Seg>();

        void add(int row, int col, int align, int color, boolean small, String text) {
            if (text == null || text.length() == 0) {
                return;
            }
            Seg s = new Seg();
            s.row = row;
            s.col = col;
            s.align = align;
            s.color = color;
            s.small = small;
            s.text = text;
            segs.add(s);
        }

        /** The packet's string: segments "row,col,align,color,small,text" separated by \u0002. */
        public String pack() {
            StringBuilder b = new StringBuilder(600);
            for (Seg s : segs) {
                if (b.length() > 0) {
                    b.append('\u0002');
                }
                b.append(s.row).append('\u0001').append(s.col).append('\u0001').append(s.align).append('\u0001').append(s.color).append('\u0001')
                        .append(s.small ? 1 : 0).append('\u0001').append(s.text);
            }
            return b.toString();
        }

        public static Screen unpack(String p) {
            Screen s = new Screen();
            if (p == null || p.length() == 0) {
                return s;
            }
            for (String e : p.split("\u0002")) {
                String[] f = e.split("\u0001", 6);
                if (f.length < 6) {
                    continue;
                }
                try {
                    s.add(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), "1".equals(f[4]), f[5]);
                } catch (NumberFormatException ex) {
                    // a malformed segment: skipped
                }
            }
            return s;
        }

        /** The text of a segment ("@key|arg|arg" through the resolver; boxes: one cell each). */
        public static String text(Seg s, Resolver r) {
            if (s.color == C_BOX) {
                return s.text;
            }
            if (s.text.startsWith("@")) {
                String[] p = s.text.substring(1).split("\\|", -1);
                String[] args = new String[p.length - 1];
                System.arraycopy(p, 1, args, 0, args.length);
                return r == null ? p[0] : r.tr(p[0], args);
            }
            return s.text;
        }

        /** The 24 x 14 cells: chars (boxes: '#'), colours, small flags. */
        public Cells cells(Resolver r) {
            Cells c = new Cells();
            for (Seg s : segs) {
                String t = text(s, r);
                if (t == null) {
                    continue;
                }
                if (t.length() > COLS) {
                    t = t.substring(0, COLS);
                }
                int start = s.align == 0 ? s.col : s.align == 1 ? s.col - t.length() : s.col - t.length() / 2;
                if (s.row < 0 || s.row >= ROWS) {
                    continue;
                }
                for (int i = 0; i < t.length(); i++) {
                    int x = start + i;
                    if (x < 0 || x >= COLS) {
                        continue;
                    }
                    c.ch[s.row][x] = s.color == C_BOX ? '#' : t.charAt(i);
                    c.col[s.row][x] = s.color;
                    c.small[s.row][x] = s.small;
                }
            }
            return c;
        }

        /** The screen as 14 lines of text (the self-test). */
        public String plain(Resolver r) {
            Cells c = cells(r);
            StringBuilder b = new StringBuilder();
            for (int y = 0; y < ROWS; y++) {
                b.append(new String(c.ch[y])).append('\n');
            }
            return b.toString();
        }
    }

    public static final class Cells {
        public final char[][] ch = new char[ROWS][COLS];
        public final int[][] col = new int[ROWS][COLS];
        public final boolean[][] small = new boolean[ROWS][COLS];

        Cells() {
            for (char[] r : ch) {
                java.util.Arrays.fill(r, ' ');
            }
        }
    }

    /** "@key|a|b": a lang key with arguments for a segment. */
    public static String k(String key, Object... args) {
        StringBuilder b = new StringBuilder("@").append(key);
        for (Object a : args) {
            b.append('|').append(String.valueOf(a).replace('|', '/'));
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- lang keys (literal - the self-test checks each)

    static final String M_BAD = "sva.mcdu.msg.badInput", M_NODB = "sva.mcdu.msg.notInDb", M_NOT = "sva.mcdu.msg.notAllowed",
            M_RANGE = "sva.mcdu.msg.range", M_FULL = "sva.mcdu.msg.planFull", M_VDIS = "sva.mcdu.msg.vDisagree", M_CHECK_TO = "sva.mcdu.msg.checkTo",
            M_SELECT = "sva.mcdu.msg.selectRwy", M_IRS = "sva.mcdu.msg.irsInit", M_STORED = "sva.mcdu.msg.stored", M_DELETED = "sva.mcdu.msg.deleted",
            M_TMPY = "sva.mcdu.msg.tmpy", M_NO_FPLN = "sva.mcdu.msg.noPlan";

    // ---------------------------------------------------------------- keys

    /** A key pressed on MCDU `si` (0 the captain's, 1 the F/O's). */
    public void key(LinerSystems L, int si, int code, Env e) {
        Side s = sides[Math.max(0, Math.min(1, si))];
        LinerFms f = L.fms;
        if (code == K_OPEN || code == K_BRT || code == K_DIM) {
            return;
        }
        if (code >= K_CHAR) {
            char c = norm((char) (code - K_CHAR));
            if (c != 0) {
                s.msg = "";
                s.clr = false;
                if (s.sp.length() < SP_MAX) {
                    s.sp += c;
                }
            }
            return;
        }
        if (code >= K_L1 && code < K_L1 + 12) {
            boolean right = code >= K_R1;
            int n = right ? code - K_R1 : code - K_L1;
            s.msg = "";
            lsk(L, f, s, n, right, e);
            return;
        }
        switch (code) {
            case K_CLR:
                if (s.msg.length() > 0) {
                    s.msg = "";
                } else if (s.clr || s.ovfy) {
                    s.clr = false;
                    s.ovfy = false;
                } else if (s.sp.length() > 0) {
                    s.sp = s.sp.substring(0, s.sp.length() - 1);
                } else {
                    s.clr = true;
                }
                return;
            case K_SP:
                if (s.sp.length() < SP_MAX && s.sp.length() > 0) {
                    s.sp += ' ';
                }
                return;
            case K_PLUSMINUS:
                if (s.sp.endsWith("-")) {
                    s.sp = s.sp.substring(0, s.sp.length() - 1) + "+";
                } else if (s.sp.endsWith("+")) {
                    s.sp = s.sp.substring(0, s.sp.length() - 1) + "-";
                } else if (s.sp.length() < SP_MAX) {
                    s.sp += "-";
                }
                return;
            case K_OVFY:
                s.ovfy = !s.ovfy;
                s.clr = false;
                return;
            case K_UP:
                s.scroll = Math.max(0, s.scroll - 1);
                return;
            case K_DOWN:
                s.scroll++;
                return;
            case K_PREV:
            case K_NEXT:
                slew(s, code == K_NEXT);
                return;
            case K_DIR:
                go(s, P_DIR);
                s.dirId = "";
                return;
            case K_PROG:
                go(s, P_PROG);
                return;
            case K_PERF:
                go(s, perfPage(f.phase));
                return;
            case K_INIT:
                go(s, P_INIT);
                return;
            case K_DATA:
                go(s, P_DATA);
                return;
            case K_FPLN:
                go(s, P_FPLN);
                return;
            case K_AIRPORT: {
                go(s, P_FPLN);
                List<Line> ls = lines(f);
                for (int i = 0; i < ls.size(); i++) {
                    if (ls.get(i).idx >= 0 && f.shown().get(ls.get(i).idx).kind == LinerFms.K_DEST) {
                        s.scroll = Math.max(0, i - 1);
                    }
                }
                return;
            }
            case K_RAD:
                go(s, P_RAD);
                return;
            case K_FUEL:
                go(s, P_FUEL);
                return;
            case K_MENU:
                go(s, P_MENU);
                return;
            default:
                s.msg = M_NOT;
                s.msgAmber = false;
        }
    }

    static void go(Side s, int page) {
        s.page = page;
        s.scroll = 0;
        s.confirm = 0;
        s.clr = false;
    }

    /** The PERF page of a phase. */
    public static int perfPage(int phase) {
        switch (phase) {
            case LinerFms.PH_CLB:
                return P_PERF_CLB;
            case LinerFms.PH_CRZ:
                return P_PERF_CRZ;
            case LinerFms.PH_DES:
                return P_PERF_DES;
            case LinerFms.PH_APPR:
            case LinerFms.PH_DONE:
                return P_PERF_APPR;
            case LinerFms.PH_GA:
                return P_PERF_GA;
            default:
                return P_PERF_TO;
        }
    }

    /** ◀ / ▶: the page's neighbours (INIT A / B, the PERF pages). */
    static void slew(Side s, boolean next) {
        int p = s.page;
        if (p == P_INIT || p == P_INITB) {
            go(s, p == P_INIT ? P_INITB : P_INIT);
        } else if (p >= P_PERF_TO && p <= P_PERF_GA) {
            int q = p + (next ? 1 : -1);
            go(s, q < P_PERF_TO ? P_PERF_GA : q > P_PERF_GA ? P_PERF_TO : q);
        } else if (p == P_FPLN || p == P_DIR || p == P_ILS_LIST || p == P_APT_LIST || p == P_WPT_LIST) {
            s.scroll = Math.max(0, s.scroll + (next ? 5 : -5));
        }
    }

    /** A typed character normalised for the scratchpad (upper case; Ё as Е), 0: not one it takes. */
    public static char norm(char c) {
        char u = Character.toUpperCase(c);
        if (u == 'Ё') {
            u = 'Е';
        }
        if (u >= 'A' && u <= 'Z' || u >= 'А' && u <= 'Я' || u >= '0' && u <= '9' || u == '/' || u == '+' || u == '-' || u == '.' || u == ','
                || u == ' ') {
            return u;
        }
        return 0;
    }

    // ---------------------------------------------------------------- parsing (public: the self-test)

    /** An integer in lo..hi, or Integer.MIN_VALUE (malformed / out of range). */
    public static int num(String s, int lo, int hi) {
        if (s == null) {
            return Integer.MIN_VALUE;
        }
        s = s.trim();
        if (s.startsWith("+")) {
            s = s.substring(1);
        }
        if (s.length() == 0 || s.length() > 9) {
            return Integer.MIN_VALUE;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9' || c == '-' && i == 0 && s.length() > 1)) {
                return Integer.MIN_VALUE;
            }
        }
        int v = Integer.parseInt(s);
        return v < lo || v > hi ? Integer.MIN_VALUE : v;
    }

    /** A number with a decimal point / comma (one decimal at most) x 10, in lo..hi tenths; Integer.MIN_VALUE bad. */
    public static int tenths(String s, int lo, int hi) {
        if (s == null) {
            return Integer.MIN_VALUE;
        }
        s = s.trim().replace(',', '.');
        boolean neg = s.startsWith("-");
        if (s.startsWith("+") || neg) {
            s = s.substring(1);
        }
        int dot = s.indexOf('.');
        String a = dot < 0 ? s : s.substring(0, dot), b = dot < 0 ? "" : s.substring(dot + 1);
        if (b.length() > 1 || a.length() == 0 && b.length() == 0 || a.length() > 6) {
            return Integer.MIN_VALUE;
        }
        for (char c : (a + b).toCharArray()) {
            if (c < '0' || c > '9') {
                return Integer.MIN_VALUE;
            }
        }
        int v = (a.length() == 0 ? 0 : Integer.parseInt(a)) * 10 + (b.length() == 0 ? 0 : b.charAt(0) - '0');
        v = neg ? -v : v;
        return v < lo || v > hi ? Integer.MIN_VALUE : v;
    }

    /** World coordinates "X1234/Z-567", "1234/-567" (Latin or Cyrillic Х / З) -> {x, z}; null malformed. */
    public static int[] coords(String s) {
        if (s == null) {
            return null;
        }
        int sl = s.indexOf('/');
        if (sl < 0 || s.indexOf('/', sl + 1) >= 0) {
            return null;
        }
        String a = s.substring(0, sl).trim(), b = s.substring(sl + 1).trim();
        if (a.startsWith("X") || a.startsWith("Х")) {
            a = a.substring(1);
        }
        if (b.startsWith("Z") || b.startsWith("З")) {
            b = b.substring(1);
        }
        int x = num(a, -RouteMath.COORD_MAX, RouteMath.COORD_MAX), z = num(b, -RouteMath.COORD_MAX, RouteMath.COORD_MAX);
        if (x == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            return null;
        }
        return new int[] {x, z};
    }

    /** An altitude constraint: "+150" at or above, "-150" at or below, "150" at -> {kind, alt}; null malformed. */
    public static int[] altCst(String s) {
        if (s == null || s.length() == 0) {
            return null;
        }
        int kind = s.startsWith("+") ? LinerFms.C_ABOVE : s.startsWith("-") ? LinerFms.C_BELOW : LinerFms.C_AT;
        int v = num(kind == LinerFms.C_AT ? s : s.substring(1), LinerFms.ALT_MIN, LinerFms.ALT_MAX);
        return v == Integer.MIN_VALUE ? null : new int[] {kind, v};
    }

    /**
     * The F-PLN's right LSK entry "SPD/ALT" (A320): "250/+150", "/+150" (altitude only), "250/" or "250" (speed only) ->
     * {speed or -1, kind or -1, alt}; null malformed.
     */
    public static int[] spdAlt(String s) {
        if (s == null || s.length() == 0) {
            return null;
        }
        int sl = s.indexOf('/');
        String a = sl < 0 ? s : s.substring(0, sl), b = sl < 0 ? "" : s.substring(sl + 1);
        int spd = -1;
        if (a.length() > 0) {
            spd = num(a, LinerFms.SPD_MIN, LinerFms.SPD_MAX);
            if (spd == Integer.MIN_VALUE) {
                return null;
            }
        }
        int[] c = null;
        if (b.length() > 0) {
            c = altCst(b);
            if (c == null) {
                return null;
            }
        }
        if (spd < 0 && c == null) {
            return null;
        }
        return new int[] {spd, c == null ? -1 : c[0], c == null ? 0 : c[1]};
    }

    /** A frequency "110.30" / "110,3" / "11030" -> hundredths of MHz on the 0.05 grid; Integer.MIN_VALUE bad. */
    public static int freq(String s) {
        if (s == null) {
            return Integer.MIN_VALUE;
        }
        s = s.trim().replace(',', '.');
        int v;
        int dot = s.indexOf('.');
        if (dot < 0) {
            v = num(s, LinerIls.FREQ_MIN, LinerIls.FREQ_MAX);
        } else {
            String a = s.substring(0, dot), b = s.substring(dot + 1);
            if (b.length() == 1) {
                b += "0";
            }
            int ia = num(a, 108, 111), ib = b.length() == 2 ? num(b, 0, 99) : Integer.MIN_VALUE;
            v = ia == Integer.MIN_VALUE || ib == Integer.MIN_VALUE ? Integer.MIN_VALUE : ia * 100 + ib;
        }
        if (v == Integer.MIN_VALUE || v < LinerIls.FREQ_MIN || v > LinerIls.FREQ_MAX || v % 5 != 0) {
            return Integer.MIN_VALUE;
        }
        return v;
    }

    /** "270/15" -> {deg 0..360, km/h 0..150}; null bad. */
    public static int[] wind(String s) {
        int sl = s == null ? -1 : s.indexOf('/');
        if (sl < 0) {
            return null;
        }
        int d = num(s.substring(0, sl), 0, 360), v = num(s.substring(sl + 1), 0, 150);
        return d == Integer.MIN_VALUE || v == Integer.MIN_VALUE ? null : new int[] {d % 360, v};
    }

    /** "A/B" split once (no slash: {s, ""}). */
    static String[] two(String s) {
        int sl = s.indexOf('/');
        return sl < 0 ? new String[] {s, ""} : new String[] {s.substring(0, sl), s.substring(sl + 1)};
    }

    /** A waypoint from the scratchpad: coordinates (a new «Т<n>»), a waypoint of the database, an airport (its point). */
    static LinerFms.Wp parseWpt(String sp, LinerFms f, Env e, String[] why) {
        int[] c = coords(sp);
        if (c != null) {
            return LinerFms.wpt(f.nextName(), c[0] + 0.5, c[1] + 0.5);
        }
        String id = NavDb.cleanIdent(sp);
        if (id.length() == 0 || !id.equals(sp.trim())) {
            why[0] = M_BAD;
            return null;
        }
        NavDb.Wpt w = e.db.wpt(id);
        if (w != null) {
            return LinerFms.wpt(w.ident, w.x, w.z);
        }
        NavDb.Airport a = e.db.airport(id);
        if (a != null) {
            return LinerFms.wpt(a.ident, a.x, a.z);
        }
        // a waypoint already in the plan by that name
        for (LinerFms.Wp p : f.shown()) {
            if (p.point() && p.id.equals(id)) {
                return LinerFms.wpt(p.id, p.x, p.z);
            }
        }
        why[0] = M_NODB;
        return null;
    }

    // ---------------------------------------------------------------- the F-PLN's lines

    /** A line of the F-PLN page: an entry (idx), T/C, T/D, the plan's end. */
    public static final class Line {
        public int idx = -1, kind;
    }

    public static final int LN_ENTRY = 0, LN_TC = 1, LN_TD = 2, LN_END = 3;

    /** The F-PLN's lines from the active leg's FROM entry: the entries with T/C and T/D among them, then the end. */
    public static List<Line> lines(LinerFms f) {
        List<Line> out = new ArrayList<Line>();
        List<LinerFms.Wp> l = f.shown();
        boolean tmpy = f.tmp != null;
        int from = Math.max(0, f.shownActive() - 1);
        boolean tc = tmpy || Double.isNaN(f.sTc), td = tmpy || Double.isNaN(f.sTd);
        for (int i = from; i < l.size(); i++) {
            LinerFms.Wp w = l.get(i);
            if (!tc && i > from && f.sTc <= w.s) {
                out.add(line(-1, LN_TC));
                tc = true;
            }
            if (!td && i > from && f.sTd <= w.s && f.sTd >= f.sAc - 1) {
                out.add(line(-1, LN_TD));
                td = true;
            }
            out.add(line(i, LN_ENTRY));
        }
        out.add(line(-1, LN_END));
        return out;
    }

    static Line line(int idx, int kind) {
        Line n = new Line();
        n.idx = idx;
        n.kind = kind;
        return n;
    }

    // ---------------------------------------------------------------- LSK actions

    void lsk(LinerSystems L, LinerFms f, Side s, int n, boolean right, Env e) {
        String sp = s.sp.trim();
        boolean clr = s.clr;
        s.clr = false;
        String[] why = {M_BAD};
        switch (s.page) {
            case P_INIT:
                lskInit(f, s, n, right, sp, clr, e);
                return;
            case P_INITB:
                lskInitB(f, s, n, right, sp, clr, e);
                return;
            case P_IRS:
                if (!right && n == 1 && sp.length() > 0) {
                    NavDb.Airport a = e.db.airport(NavDb.cleanIdent(sp));
                    if (a == null) {
                        fail(s, M_NODB);
                    } else {
                        f.irsRef = a.ident;
                        done(s);
                    }
                } else if (right && n == 5 && !e.autoIrs && !f.irsDone) {
                    f.irsDone = true;
                    s.msg = "";
                } else if (!right && n == 5) {
                    go(s, P_INIT);
                }
                return;
            case P_FPLN:
                lskFpln(f, s, n, right, sp, clr, e, why);
                return;
            case P_LATREV:
                lskLatRev(f, s, n, right, sp, e, why);
                return;
            case P_VERTREV:
                lskVertRev(f, s, n, right, sp, clr);
                return;
            case P_DIR:
                lskDir(f, s, n, right, sp, e, why);
                return;
            case P_PERF_TO:
            case P_PERF_CLB:
            case P_PERF_CRZ:
            case P_PERF_DES:
            case P_PERF_APPR:
            case P_PERF_GA:
                lskPerf(L, f, s, n, right, sp, clr, e);
                return;
            case P_FUEL:
                if (!right && n == 3) {
                    int v = num(sp, 0, 20000);
                    if (v == Integer.MIN_VALUE) {
                        fail(s, M_BAD);
                    } else {
                        f.reserve = v;
                        done(s);
                    }
                }
                return;
            case P_PROG:
                if (!right && n == 0 && sp.length() > 0) {
                    int v = num(sp, LinerFms.CRZ_MIN, LinerFms.CRZ_MAX);
                    if (v == Integer.MIN_VALUE) {
                        fail(s, M_RANGE);
                    } else {
                        f.crz = v;
                        f.rev++;
                        done(s);
                    }
                } else if (!right && n == 2) {
                    if (clr) {
                        s.progTo = "";
                    } else if (sp.length() > 0) {
                        LinerFms.Wp w = parseWpt(sp, f, e, why);
                        if (w == null) {
                            fail(s, why[0]);
                        } else {
                            s.progTo = coords(sp) != null ? sp : w.id;
                            done(s);
                        }
                    }
                }
                return;
            case P_RAD:
                lskRad(L, s, n, right, sp, clr, e);
                return;
            case P_ILS_LIST: {
                List<LinerIls.Station> st = nearIls(e);
                int i = s.scroll + n;
                if (!right && n < 5 && i < st.size()) {
                    L.afs.ilsChosen = st.get(i).key();
                    go(s, P_RAD);
                } else if (!right && n == 5) {
                    go(s, P_RAD);
                }
                return;
            }
            case P_DATA:
                if (!right && n == 0) {
                    go(s, P_POS);
                } else if (right && n == 0) {
                    go(s, P_APT_LIST);
                } else if (right && n == 1) {
                    go(s, P_WPT_LIST);
                } else if (right && n == 2) {
                    go(s, P_ILS_LIST);
                }
                return;
            case P_APT_LIST:
                lskAptList(s, n, right, sp, clr, e);
                return;
            case P_WPT_LIST:
                lskWptList(s, n, right, sp, clr, e);
                return;
            case P_APT:
            case P_POS:
                if (right && n == 5) {
                    go(s, s.page == P_APT ? P_APT_LIST : P_DATA);
                }
                return;
            case P_MENU:
                if (!right && n == 0) {
                    go(s, P_INIT);
                }
                return;
            default:
        }
    }

    static void fail(Side s, String msg) {
        s.msg = msg;
        s.msgAmber = false;
    }

    static void done(Side s) {
        s.sp = "";
        s.msg = "";
    }

    private void lskInit(LinerFms f, Side s, int n, boolean right, String sp, boolean clr, Env e) {
        if (right && n == 0) {
            String[] p = two(sp);
            String a = NavDb.cleanIdent(p[0]), b = NavDb.cleanIdent(p[1]);
            if (a.length() == 0 || b.length() == 0) {
                fail(s, M_BAD);
                return;
            }
            NavDb.Airport fa = e.db.airport(a), ta = e.db.airport(b);
            if (fa == null || ta == null) {
                fail(s, M_NODB);
                return;
            }
            f.setFromTo(fa, ta);
            done(s);
        } else if (right && n == 1) {
            go(s, P_IRS);
        } else if (!right && n == 0) {
            if (clr) {
                f.flt = "";
            } else if (sp.length() > 0) {
                if (sp.length() > 8) {
                    fail(s, M_BAD);
                    return;
                }
                f.flt = sp;
                done(s);
            }
        } else if (!right && n == 1) {
            if (clr) {
                f.altn = "";
            } else if (sp.length() > 0) {
                NavDb.Airport a = e.db.airport(NavDb.cleanIdent(sp));
                if (a == null) {
                    fail(s, M_NODB);
                    return;
                }
                f.altn = a.ident;
                done(s);
            }
        } else if (!right && n == 3) {
            int v = num(sp, 0, LinerFms.CI_MAX);
            if (v == Integer.MIN_VALUE) {
                fail(s, sp.length() == 0 ? M_BAD : M_RANGE);
                return;
            }
            f.ci = v;
            f.rev++;
            done(s);
        } else if (!right && n == 4) {
            String[] p = two(sp);
            int alt = p[0].length() == 0 ? f.crz : num(p[0], LinerFms.CRZ_MIN, LinerFms.CRZ_MAX);
            int t = p[1].length() == 0 ? f.crzTemp : num(p[1], -60, 60);
            if (alt == Integer.MIN_VALUE || t == Integer.MIN_VALUE || sp.length() == 0) {
                fail(s, M_RANGE);
                return;
            }
            f.crz = alt;
            f.crzTemp = t;
            f.rev++;
            done(s);
        } else if (!right && n == 5) {
            String[] p = two(sp);
            int z = p[0].length() == 0 ? f.zfw10 : tenths(p[0], 100, 400);
            int b = p[1].length() == 0 ? f.block : num(p[1], 0, 60000);
            if (z == Integer.MIN_VALUE || b == Integer.MIN_VALUE || sp.length() == 0) {
                fail(s, M_BAD);
                return;
            }
            f.zfw10 = z;
            f.block = b;
            done(s);
        } else if (right && n == 5) {
            go(s, P_INITB);
        }
    }

    private void lskInitB(LinerFms f, Side s, int n, boolean right, String sp, boolean clr, Env e) {
        if (!right && n == 0) {
            int v = num(sp, 0, 2000);
            if (v == Integer.MIN_VALUE) {
                fail(s, M_BAD);
            } else {
                f.taxi = v;
                done(s);
            }
        } else if (!right && n == 2) {
            int v = num(sp, 0, 20000);
            if (v == Integer.MIN_VALUE) {
                fail(s, M_BAD);
            } else {
                f.reserve = v;
                done(s);
            }
        } else if (right && n == 0) {
            int v = tenths(sp, 100, 400);
            if (v == Integer.MIN_VALUE) {
                fail(s, M_BAD);
            } else {
                f.zfw10 = v;
                done(s);
            }
        } else if (right && n == 1) {
            if (sp.length() == 0) {
                f.block = (int) Math.round(e.fob);                 // the empty press: the fuel on board
                return;
            }
            int v = num(sp, 0, 60000);
            if (v == Integer.MIN_VALUE) {
                fail(s, M_BAD);
            } else {
                f.block = v;
                done(s);
            }
        } else if (!right && n == 5) {
            go(s, P_INIT);
        }
    }

    /** The F-PLN line on LSK n (0..4) of side s (null none). */
    static Line lineAt(LinerFms f, Side s, int n) {
        List<Line> ls = lines(f);
        s.scroll = Math.max(0, Math.min(s.scroll, Math.max(0, ls.size() - 1)));
        int i = s.scroll + n;
        return n < 5 && i < ls.size() ? ls.get(i) : null;
    }

    private void lskFpln(LinerFms f, Side s, int n, boolean right, String sp, boolean clr, Env e, String[] why) {
        if (n == 5) {
            if (f.tmp != null) {
                if (right) {
                    f.tmpInsert();
                } else {
                    f.tmpErase();
                }
            }
            return;
        }
        Line ln = lineAt(f, s, n);
        if (ln == null) {
            return;
        }
        List<LinerFms.Wp> l = f.shown();
        if (ln.kind != LN_ENTRY) {
            if (ln.kind == LN_END && !right && sp.length() > 0) {
                fail(s, M_NOT);
            } else if (clr || sp.length() > 0) {
                fail(s, M_NOT);
            }
            return;
        }
        LinerFms.Wp w = l.get(ln.idx);
        if (!right) {
            if (clr) {
                if (!f.deleteAt(ln.idx)) {
                    fail(s, M_NOT);
                }
                return;
            }
            if (s.ovfy) {
                s.ovfy = false;
                if (w.point() && w.kind == LinerFms.K_WPT) {
                    w.ovfy = !w.ovfy;
                    f.rev++;
                } else {
                    fail(s, M_NOT);
                }
                return;
            }
            if (sp.length() > 0) {
                LinerFms.Wp nw = parseWpt(sp, f, e, why);
                if (nw == null) {
                    fail(s, why[0]);
                    return;
                }
                if (!f.insertAt(ln.idx, nw)) {
                    fail(s, l.size() >= LinerFms.MAX ? M_FULL : M_NOT);
                    return;
                }
                done(s);
                return;
            }
            if (w.point()) {
                s.selId = w.id;
                go(s, P_LATREV);
            }
            return;
        }
        // right: the constraints
        if (!w.point() || w.kind == LinerFms.K_ORIG || w.kind == LinerFms.K_DEST) {
            if (clr || sp.length() > 0) {
                fail(s, M_NOT);
            }
            return;
        }
        if (clr) {
            f.setCst(ln.idx, 0, LinerFms.C_NONE, 0);
            return;
        }
        if (sp.length() > 0) {
            int[] c = spdAlt(sp);
            if (c == null) {
                fail(s, M_BAD);
                return;
            }
            f.setCst(ln.idx, c[0], c[1], c[2]);
            done(s);
            return;
        }
        s.selId = w.id;
        go(s, P_VERTREV);
    }

    /** The shown plan's index of the side's selected entry (-1: gone). */
    static int selIndex(LinerFms f, Side s) {
        List<LinerFms.Wp> l = f.shown();
        for (int i = Math.max(0, f.shownActive() - 1); i < l.size(); i++) {
            if (l.get(i).point() && l.get(i).id.equals(s.selId)) {
                return i;
            }
        }
        return -1;
    }

    private void lskLatRev(LinerFms f, Side s, int n, boolean right, String sp, Env e, String[] why) {
        int i = selIndex(f, s);
        if (i < 0 || !right && n == 5) {
            go(s, P_FPLN);
            return;
        }
        LinerFms.Wp w = f.shown().get(i);
        if (!right && n == 0) {
            if (w.kind == LinerFms.K_ORIG || i < f.shownActive() - 1) {
                fail(s, M_NOT);
                return;
            }
            f.tmpErase();
            int j = selIndex(f, s);
            if (j < 0 || !f.dirTo(j, e.x, e.z)) {
                fail(s, M_NOT);
                return;
            }
            go(s, P_FPLN);
        } else if (right && n == 0 && w.kind == LinerFms.K_DEST) {
            NavDb.Airport a = e.db.airport(f.to);
            String r = sp.length() == 1 ? "0" + sp : sp;
            if (a == null || a.rwy(r) == null || !a.rwy(r).id.equals(r)) {
                fail(s, sp.length() == 0 ? M_SELECT : M_NODB);
                return;
            }
            f.setDestRwy(a, r);
            s.selId = f.dest().id;
            done(s);
        } else if (right && n == 2 && sp.length() > 0) {
            if (w.kind == LinerFms.K_DEST || w.kind == LinerFms.K_MISSED) {
                fail(s, M_NOT);
                return;
            }
            LinerFms.Wp nw = parseWpt(sp, f, e, why);
            if (nw == null) {
                fail(s, why[0]);
                return;
            }
            if (!f.insertAt(i + 1, nw)) {
                fail(s, M_NOT);
                return;
            }
            done(s);
            go(s, P_FPLN);
        }
    }

    private void lskVertRev(LinerFms f, Side s, int n, boolean right, String sp, boolean clr) {
        int i = selIndex(f, s);
        if (i < 0 || !right && n == 5) {
            go(s, P_FPLN);
            return;
        }
        if (!right && n == 1) {
            if (clr) {
                f.setCst(i, 0, -1, 0);
                return;
            }
            int v = num(sp, LinerFms.SPD_MIN, LinerFms.SPD_MAX);
            if (v == Integer.MIN_VALUE || !f.setCst(i, v, -1, 0)) {
                fail(s, sp.length() == 0 ? M_BAD : M_RANGE);
                return;
            }
            done(s);
        } else if (!right && n == 2) {
            if (clr) {
                f.setCst(i, -1, LinerFms.C_NONE, 0);
                return;
            }
            int[] c = altCst(sp);
            if (c == null || !f.setCst(i, -1, c[0], c[1])) {
                fail(s, M_BAD);
                return;
            }
            done(s);
        }
    }

    /** DIR TO's list: the plan's waypoints ahead (indices into pts). */
    static List<Integer> dirList(LinerFms f) {
        List<Integer> r = new ArrayList<Integer>();
        for (int i = Math.max(1, f.active); i < f.pts.size(); i++) {
            LinerFms.Wp w = f.pts.get(i);
            if (w.point() && w.kind != LinerFms.K_ORIG) {
                r.add(Integer.valueOf(i));
            }
        }
        return r;
    }

    private void lskDir(LinerFms f, Side s, int n, boolean right, String sp, Env e, String[] why) {
        if (n == 5) {
            if (right && f.tmp != null) {
                f.tmpInsert();
                go(s, P_FPLN);
            } else if (!right) {
                f.tmpErase();
                s.dirId = "";
            }
            return;
        }
        if (!f.hasPlan() && !(n == 0 && sp.length() > 0)) {
            fail(s, M_NO_FPLN);
            return;
        }
        if (!right && n == 0 && sp.length() > 0) {
            LinerFms.Wp nw = parseWpt(sp, f, e, why);
            if (nw == null) {
                fail(s, why[0]);
                return;
            }
            f.tmpErase();
            int at = -1;
            for (Integer k : dirList(f)) {
                if (f.pts.get(k.intValue()).id.equals(nw.id)) {
                    at = k.intValue();
                }
            }
            boolean ok = at >= 0 ? f.dirTo(at, e.x, e.z) : f.dirToNew(nw, e.x, e.z);
            if (!ok) {
                fail(s, M_NOT);
                return;
            }
            s.dirId = nw.id;
            done(s);
            return;
        }
        if (!right && n >= 1 && n <= 4) {
            List<Integer> d = dirList(f);
            int i = s.scroll + n - 1;
            if (i < d.size()) {
                f.tmpErase();
                int idx = d.get(i).intValue();
                String id = f.pts.get(idx).id;
                if (f.dirTo(idx, e.x, e.z)) {
                    s.dirId = id;
                }
            }
        }
    }

    private void lskPerf(LinerSystems L, LinerFms f, Side s, int n, boolean right, String sp, boolean clr, Env e) {
        int p = s.page;
        if (right && n == 5 && p != P_PERF_GA) {
            go(s, p + 1);
            return;
        }
        if (!right && n == 5 && p != P_PERF_TO) {
            go(s, p - 1);
            return;
        }
        // the approach phase's activation (CRZ / DES / APPR: L5, two presses)
        if (!right && n == 4 && (p == P_PERF_CRZ || p == P_PERF_DES || p == P_PERF_APPR) && !f.apprActive && !e.ground) {
            if (s.confirm == 0) {
                s.confirm = 1;
            } else {
                f.apprActive = true;
                s.confirm = 0;
            }
            return;
        }
        if (!right && n == 0 && p >= P_PERF_CLB && p <= P_PERF_DES) {
            int v = num(sp, 0, LinerFms.CI_MAX);
            if (v == Integer.MIN_VALUE) {
                fail(s, sp.length() == 0 ? M_BAD : M_RANGE);
            } else {
                f.ci = v;
                f.rev++;
                done(s);
            }
            return;
        }
        if (p == P_PERF_TO) {
            if (!right && n <= 2) {
                if (clr) {
                    if (n == 0) {
                        f.v1 = 0;
                    } else if (n == 1) {
                        f.vr = 0;
                    } else {
                        f.v2 = 0;
                    }
                    return;
                }
                int v = num(sp, 60, 200);
                if (v == Integer.MIN_VALUE) {
                    fail(s, sp.length() == 0 ? M_BAD : M_RANGE);
                    return;
                }
                int o1 = f.v1, o2 = f.vr, o3 = f.v2;
                if (n == 0) {
                    f.v1 = v;
                } else if (n == 1) {
                    f.vr = v;
                } else {
                    f.v2 = v;
                }
                if (f.v1 > 0 && f.vr > 0 && f.v1 > f.vr || f.vr > 0 && f.v2 > 0 && f.vr > f.v2 || f.v1 > 0 && f.v2 > 0 && f.v1 > f.v2) {
                    f.v1 = o1;
                    f.vr = o2;
                    f.v2 = o3;
                    fail(s, M_VDIS);
                    return;
                }
                f.towAtV = f.tow(e.riders, e.fob);
                done(s);
            } else if (right && n == 4) {
                // «ВЫЧИСЛИТЬ»: the V-speeds from the take-off weight, the flaps and the runway
                LinerFms.Wp o = f.origin();
                NavDb.Airport a = o == null ? null : e.db.airport(f.from);
                NavDb.Rwy r = a == null ? null : a.rwy(o.rwy);
                int[] v = LinerFms.computeV(f.tow(e.riders, e.fob), f.toFlaps > 0 ? f.toFlaps : Math.max(1, L.at(LinerControls.FLAPS)),
                        r == null ? 0 : r.len);
                f.v1 = v[0];
                f.vr = v[1];
                f.v2 = v[2];
                f.towAtV = f.tow(e.riders, e.fob);
            } else if (right && n == 2) {
                if (clr) {
                    f.toFlaps = 0;
                    return;
                }
                String[] q = two(sp);
                int fl = q[0].length() == 0 ? f.toFlaps : num(q[0], 1, 3);
                int th = q[1].length() == 0 ? f.ths10 : tenths(q[1].replace("КАБ", "+").replace("ПИК", "-"), -25, 25);
                if (fl == Integer.MIN_VALUE || th == Integer.MIN_VALUE || sp.length() == 0) {
                    fail(s, M_BAD);
                    return;
                }
                f.toFlaps = fl;
                f.ths10 = th;
                done(s);
            } else if (!right && n == 3) {
                perfThrAcc(f, s, sp, false);
            } else if (right && n == 3) {
                if (clr) {
                    f.flex = 0;
                    return;
                }
                int v = num(sp, LinerFms.FLEX_MIN, LinerFms.FLEX_MAX);
                if (v == Integer.MIN_VALUE) {
                    fail(s, sp.length() == 0 ? M_BAD : M_RANGE);
                    return;
                }
                f.flex = v;
                done(s);
            }
            return;
        }
        if (p == P_PERF_APPR) {
            if (!right && n == 0) {
                int v = num(sp, LinerAfs.QNH_MIN, LinerAfs.QNH_MAX);
                if (v == Integer.MIN_VALUE) {
                    fail(s, M_RANGE);
                } else {
                    f.qnh = v;
                    done(s);
                }
            } else if (!right && n == 1) {
                int v = num(sp, -60, 60);
                if (v == Integer.MIN_VALUE) {
                    fail(s, M_RANGE);
                } else {
                    f.apprTemp = v;
                    done(s);
                }
            } else if (!right && n == 2) {
                int[] w = wind(sp);
                if (w == null) {
                    fail(s, M_BAD);
                } else {
                    f.windDir = w[0];
                    f.windSpd = w[1];
                    done(s);
                }
            } else if (!right && n == 3) {
                if (clr) {
                    f.vapp = 0;
                    return;
                }
                int v = num(sp, 60, 200);
                if (v == Integer.MIN_VALUE) {
                    fail(s, M_RANGE);
                } else {
                    f.vapp = v;
                    done(s);
                }
            } else if (right && (n == 1 || n == 2)) {
                if (clr) {
                    if (n == 1) {
                        f.dh = -1;
                    } else {
                        f.mda = -1;
                    }
                    return;
                }
                int v = num(sp, 0, n == 1 ? 100 : 255);
                if (v == Integer.MIN_VALUE) {
                    fail(s, M_RANGE);
                    return;
                }
                if (n == 1) {
                    f.dh = v;
                    f.mda = -1;
                } else {
                    f.mda = v;
                    f.dh = -1;
                }
                done(s);
            } else if (right && n == 3) {
                f.ldgConf = f.ldgConf == 3 ? 4 : 3;
            }
            return;
        }
        if (p == P_PERF_GA && !right && n == 4) {
            perfThrAcc(f, s, sp, true);
        }
    }

    private static void perfThrAcc(LinerFms f, Side s, String sp, boolean ga) {
        String[] q = two(sp);
        int t = q[0].length() == 0 ? (ga ? f.gaThrRed : f.thrRed) : num(q[0], 5, 150);
        int a = q[1].length() == 0 ? (ga ? f.gaAcc : f.acc) : num(q[1], 5, 150);
        if (t == Integer.MIN_VALUE || a == Integer.MIN_VALUE || sp.length() == 0 || t > 0 && a > 0 && a < t) {
            fail(s, sp.length() == 0 ? M_BAD : M_RANGE);
            return;
        }
        if (ga) {
            f.gaThrRed = t;
            f.gaAcc = a;
        } else {
            f.thrRed = t;
            f.acc = a;
        }
        done(s);
    }

    /** The ILS stations near, nearest first. */
    static List<LinerIls.Station> nearIls(Env e) {
        List<LinerIls.Station> l = new ArrayList<LinerIls.Station>(e.ils);
        final double x = e.x, z = e.z;
        java.util.Collections.sort(l, new java.util.Comparator<LinerIls.Station>() {
            @Override
            public int compare(LinerIls.Station p, LinerIls.Station q) {
                return Double.compare(sq(p.tx() - x) + sq(p.tz() - z), sq(q.tx() - x) + sq(q.tz() - z));
            }
        });
        return l;
    }

    private void lskRad(LinerSystems L, Side s, int n, boolean right, String sp, boolean clr, Env e) {
        if (!right && n == 0) {
            if (clr) {
                L.afs.ilsChosen = "";                               // back to automatic (the plan's, else the nearest)
                return;
            }
            if (sp.length() == 0) {
                return;
            }
            int fq = freq(sp);
            String id = fq == Integer.MIN_VALUE ? LinerIls.cleanIdent(sp) : "";
            if (fq == Integer.MIN_VALUE && (id.length() == 0 || !id.equals(sp))) {
                fail(s, M_BAD);
                return;
            }
            LinerIls.Station st = e.db.ils(id, fq, e.x, e.z);
            if (st == null) {
                for (LinerIls.Station o : nearIls(e)) {
                    if (id.length() > 0 ? id.equals(o.ident) : o.freq == fq) {
                        st = o;
                        break;
                    }
                }
            }
            if (st == null) {
                fail(s, M_NODB);
                return;
            }
            L.afs.ilsChosen = st.key();
            done(s);
        } else if (!right && n == 5) {
            go(s, P_ILS_LIST);
        }
    }

    private void lskAptList(Side s, int n, boolean right, String sp, boolean clr, Env e) {
        if (right && n == 5) {
            go(s, P_DATA);
            return;
        }
        if (!right && n < 5) {
            int i = s.scroll + n;
            if (i >= e.db.airports.size()) {
                return;
            }
            NavDb.Airport a = e.db.airports.get(i);
            if (clr) {
                String m = a.src != NavDb.SRC_PILOT || e.store == null ? M_NOT : e.store.delete(a.ident, true);
                s.msg = m == null ? M_DELETED : m;
                return;
            }
            s.selId = a.ident;
            go(s, P_APT);
            return;
        }
        if (!right && n == 5 && sp.length() > 0) {
            // a pilot's airport: ID/X/Z[/ELEV[/COURSE[/LENGTH]]]
            String[] p = sp.split("/");
            String id = p.length > 0 ? NavDb.cleanIdent(p[0]) : "";
            int x = p.length > 2 ? num(p[1].replace("X", "").replace("Х", ""), -RouteMath.COORD_MAX, RouteMath.COORD_MAX) : Integer.MIN_VALUE;
            int z = p.length > 2 ? num(p[2].replace("Z", "").replace("З", ""), -RouteMath.COORD_MAX, RouteMath.COORD_MAX) : Integer.MIN_VALUE;
            int el = p.length > 3 ? num(p[3], 1, 255) : (int) Math.round(e.y);
            int hd = p.length > 4 ? num(p[4], 0, 360) : 0;
            int ln = p.length > 5 ? num(p[5], 20, 5000) : NavDb.DEFAULT_LEN;
            if (id.length() == 0 || x == Integer.MIN_VALUE || z == Integer.MIN_VALUE || el == Integer.MIN_VALUE || hd == Integer.MIN_VALUE
                    || ln == Integer.MIN_VALUE || p.length > 6) {
                fail(s, M_BAD);
                return;
            }
            String m = e.store == null ? M_NOT : e.store.putApt(NavDb.pilot(id, e.dim, x + 0.5, z + 0.5, el, hd, ln));
            if (m != null) {
                fail(s, m);
                return;
            }
            done(s);
            s.msg = M_STORED;
        }
    }

    private void lskWptList(Side s, int n, boolean right, String sp, boolean clr, Env e) {
        if (right && n == 5) {
            go(s, P_DATA);
            return;
        }
        if (!right && n < 5) {
            int i = s.scroll + n;
            if (i < e.db.wpts.size() && clr) {
                NavDb.Wpt w = e.db.wpts.get(i);
                String m = w.src != NavDb.SRC_PILOT || e.store == null ? M_NOT : e.store.delete(w.ident, false);
                s.msg = m == null ? M_DELETED : m;
            }
            return;
        }
        if (!right && n == 5 && sp.length() > 0) {
            // ID/X/Z, or ID alone - here (the present position)
            int sl = sp.indexOf('/');
            String id = NavDb.cleanIdent(sl < 0 ? sp : sp.substring(0, sl));
            int[] c = sl < 0 ? new int[] {(int) Math.floor(e.x), (int) Math.floor(e.z)} : coords(sp.substring(sl + 1));
            if (id.length() == 0 || c == null || !id.equals(sl < 0 ? sp : sp.substring(0, sl))) {
                fail(s, M_BAD);
                return;
            }
            String m = e.store == null ? M_NOT : e.store.putWpt(NavDb.wpt(id, NavDb.SRC_PILOT, e.dim, c[0] + 0.5, c[1] + 0.5));
            if (m != null) {
                fail(s, m);
                return;
            }
            done(s);
            s.msg = M_STORED;
        }
    }

    // ---------------------------------------------------------------- the per-tick messages

    private boolean irsTold, towTold;

    /** Server, every tick: the automatic messages - the IRS waiting for its INIT, the take-off weight changed after the V-speeds. */
    public void tick(LinerSystems L, Env e) {
        LinerFms f = L.fms;
        boolean wait = L.afs.irsState == 2;
        if (wait && !irsTold) {
            say(M_IRS, true);
        }
        irsTold = wait;
        boolean tow = e.ground && f.vEntered() && f.towAtV > 0 && Math.abs(f.tow(e.riders, e.fob) - f.towAtV) > 0.3;
        if (tow && !towTold) {
            say(M_CHECK_TO, true);
        }
        towTold = tow;
    }

    /** A message on both MCDUs. */
    void say(String key, boolean amber) {
        for (Side s : sides) {
            s.msg = key;
            s.msgAmber = amber;
        }
    }

    // ---------------------------------------------------------------- render

    /** Builds a screen in rows / columns. */
    static final class R {
        final Screen sc = new Screen();

        void title(String t, int c) {
            sc.add(0, COLS / 2, 2, c, false, t);
        }

        /** LSK k's (1..6) label (small, white unless given) left / right / at a column. */
        void lbl(int k, boolean right, String t) {
            sc.add(k * 2 - 1, right ? COLS : 0, right ? 1 : 0, C_WHITE, true, t);
        }

        void lblAt(int k, int col, int align, String t, int c) {
            sc.add(k * 2 - 1, col, align, c, true, t);
        }

        void dat(int k, boolean right, String t, int c) {
            sc.add(k * 2, right ? COLS : 0, right ? 1 : 0, c, false, t);
        }

        void datAt(int k, int col, int align, String t, int c, boolean small) {
            sc.add(k * 2, col, align, c, small, t);
        }

        /** n amber boxes (mandatory) at LSK k's data line, left or right (after `before` cells). */
        void box(int k, boolean right, int n, int at) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < n; i++) {
                b.append('#');
            }
            sc.add(k * 2, right ? COLS - at : at, right ? 1 : 0, C_BOX, false, b.toString());
        }

        void scratch(String t, int c) {
            sc.add(13, 0, 0, c, false, t);
        }
    }

    /** The screen of MCDU `si` now. */
    public Screen render(LinerSystems L, int si, Env e) {
        Side s = sides[Math.max(0, Math.min(1, si))];
        LinerFms f = L.fms;
        R r = new R();
        switch (s.page) {
            case P_INIT:
                pInit(r, f, e);
                break;
            case P_INITB:
                pInitB(r, f, e);
                break;
            case P_IRS:
                pIrs(r, L, f, e);
                break;
            case P_FPLN:
                pFpln(r, f, s, e);
                break;
            case P_LATREV:
                pLatRev(r, f, s, e);
                break;
            case P_VERTREV:
                pVertRev(r, f, s);
                break;
            case P_DIR:
                pDir(r, f, s, e);
                break;
            case P_PERF_TO:
                pPerfTo(r, L, f, e);
                break;
            case P_PERF_CLB:
            case P_PERF_CRZ:
            case P_PERF_DES:
                pPerfPhase(r, L, f, s, e);
                break;
            case P_PERF_APPR:
                pPerfAppr(r, f, s, e);
                break;
            case P_PERF_GA:
                pPerfGa(r, f, e);
                break;
            case P_FUEL:
                pFuel(r, f, e);
                break;
            case P_PROG:
                pProg(r, f, s, e);
                break;
            case P_RAD:
                pRad(r, L, e);
                break;
            case P_ILS_LIST:
                pIlsList(r, L, s, e);
                break;
            case P_DATA:
                pData(r, e);
                break;
            case P_APT_LIST:
                pAptList(r, s, e);
                break;
            case P_APT:
                pApt(r, s, e);
                break;
            case P_WPT_LIST:
                pWptList(r, s, e);
                break;
            case P_POS:
                pPos(r, e);
                break;
            default:
                r.title(k("sva.mcdu.menu.title"), C_WHITE);
                r.dat(1, false, k("sva.mcdu.menu.fmgc"), C_GREEN);
                break;
        }
        // the scratchpad: a message over the entry; the armed CLR / OVFY
        if (s.msg.length() > 0) {
            r.scratch(k(s.msg), s.msgAmber ? C_AMBER : C_WHITE);
        } else if (s.clr) {
            r.scratch(k("sva.mcdu.clr"), C_WHITE);
        } else if (s.ovfy) {
            r.scratch(k("sva.mcdu.ovfy"), C_WHITE);
        } else {
            r.scratch(s.sp, C_WHITE);
        }
        return r.sc;
    }

    // ---------------------------------------------------------------- the pages

    private void pInit(R r, LinerFms f, Env e) {
        r.title(k("sva.mcdu.init.title"), C_WHITE);
        r.lbl(1, false, k("sva.mcdu.init.flt"));
        r.dat(1, false, f.flt.length() > 0 ? f.flt : "--------", f.flt.length() > 0 ? C_CYAN : C_WHITE);
        r.lbl(1, true, k("sva.mcdu.init.fromTo"));
        if (f.from.length() > 0) {
            r.dat(1, true, f.from + "/" + f.to, C_CYAN);
        } else {
            r.box(1, true, 4, 5);
            r.datAt(1, COLS - 5, 0, "/", C_AMBER, false);
            r.box(1, true, 4, 0);
        }
        r.lbl(2, false, k("sva.mcdu.init.altn"));
        r.dat(2, false, f.altn.length() > 0 ? f.altn : k("sva.mcdu.none"), C_CYAN);
        r.dat(2, true, k("sva.mcdu.init.irs"), f.irsDone || e.autoIrs ? C_WHITE : C_AMBER);
        LinerFms.Wp o = f.origin();
        double x = o != null ? o.x : e.x, z = o != null ? o.z : e.z;
        r.lbl(3, false, "X");
        r.dat(3, false, String.valueOf((int) Math.floor(x)), C_GREEN);
        r.lbl(3, true, "Z");
        r.dat(3, true, String.valueOf((int) Math.floor(z)), C_GREEN);
        r.lbl(4, false, k("sva.mcdu.init.ci"));
        if (f.ci >= 0) {
            r.dat(4, false, String.valueOf(f.ci), C_CYAN);
        } else {
            r.box(4, false, 3, 0);
        }
        r.lbl(5, false, k("sva.mcdu.init.crz"));
        if (f.crz > 0) {
            r.dat(5, false, f.crz + "/" + (f.crzTemp == LinerFms.NO_TEMP ? "---" : signed(f.crzTemp)), C_CYAN);
        } else {
            r.box(5, false, 3, 0);
            r.datAt(5, 3, 0, "/---", C_CYAN, false);
        }
        r.lbl(6, false, k("sva.mcdu.init.zfwBlock"));
        r.dat(6, false, (f.zfw10 > 0 ? t1(f.zfw10) : "--,-") + "/" + (f.block >= 0 ? group(f.block) : "-----"), f.zfw10 > 0 && f.block >= 0 ? C_CYAN
                : C_WHITE);
        r.dat(6, true, k("sva.mcdu.init.toB"), C_WHITE);
    }

    private void pInitB(R r, LinerFms f, Env e) {
        r.title(k("sva.mcdu.initb.title"), C_WHITE);
        double[] trip = f.trip(e.burn);
        double altn = f.altnFuel(e.db, e.burn);
        double fuel = f.block >= 0 ? f.block : e.fob;
        r.lbl(1, false, k("sva.mcdu.initb.taxi"));
        r.dat(1, false, group(f.taxi), C_CYAN);
        r.lbl(2, false, k("sva.mcdu.initb.trip"));
        r.dat(2, false, Double.isNaN(trip[0]) ? "---/----" : group((int) Math.round(trip[0])) + "/" + hm(trip[1]), C_GREEN);
        r.lbl(3, false, k("sva.mcdu.initb.rsv"));
        r.dat(3, false, group(f.reserve), C_CYAN);
        r.lbl(4, false, k("sva.mcdu.initb.altn"));
        r.dat(4, false, f.altn.length() == 0 ? "---" : group((int) Math.round(altn)), C_GREEN);
        r.lbl(5, false, k("sva.mcdu.initb.extra"));
        double extra = Double.isNaN(trip[0]) ? Double.NaN : fuel - f.taxi - trip[0] - f.reserve - altn;
        r.dat(5, false, Double.isNaN(extra) ? "---" : group((int) Math.round(extra)), !Double.isNaN(extra) && extra < 0 ? C_AMBER : C_GREEN);
        r.lbl(1, true, k("sva.mcdu.initb.zfw"));
        if (f.zfw10 > 0) {
            r.dat(1, true, t1(f.zfw10), C_CYAN);
        } else {
            r.box(1, true, 4, 0);
        }
        r.lbl(2, true, k("sva.mcdu.initb.block"));
        if (f.block >= 0) {
            r.dat(2, true, group(f.block), C_CYAN);
        } else {
            r.box(2, true, 5, 0);
        }
        double tow = f.tow(e.riders, e.fob);
        r.lbl(3, true, k("sva.mcdu.initb.tow"));
        r.dat(3, true, t1((int) Math.round(tow * 10)), C_GREEN);
        r.lbl(4, true, k("sva.mcdu.initb.lw"));
        double lw = Double.isNaN(trip[0]) ? Double.NaN : tow - trip[0] * LinerFms.FUEL_T;
        r.dat(4, true, Double.isNaN(lw) ? "--,-" : t1((int) Math.round(lw * 10)), C_GREEN);
        r.dat(6, false, k("sva.mcdu.back"), C_WHITE);
    }

    private void pIrs(R r, LinerSystems L, LinerFms f, Env e) {
        r.title(k("sva.mcdu.irs.title"), C_WHITE);
        NavDb.Airport ref = f.irsRef.length() > 0 ? e.db.airport(f.irsRef) : null;
        double x = ref != null ? ref.x : e.x, z = ref != null ? ref.z : e.z;
        r.lbl(1, false, k("sva.mcdu.irs.x"));
        r.dat(1, false, "<" + (int) Math.floor(x), C_CYAN);
        r.lbl(1, true, k("sva.mcdu.irs.z"));
        r.dat(1, true, (int) Math.floor(z) + ">", C_CYAN);
        r.lbl(2, false, k("sva.mcdu.irs.ref"));
        r.dat(2, false, f.irsRef.length() > 0 ? f.irsRef : "----", C_GREEN);
        r.lbl(2, true, k("sva.mcdu.irs.gps"));
        r.dat(2, true, (int) Math.floor(e.x) + "/" + (int) Math.floor(e.z), C_GREEN);
        LinerControls.Ctl[] sel = {LinerControls.ADIRS1, LinerControls.ADIRS2, LinerControls.ADIRS3};
        for (int i = 0; i < 3; i++) {
            String st;
            int c = C_GREEN;
            if (L.at(sel[i]) == 0 || e.insStatus == SystemsMath.INS_OFF) {
                st = k("sva.mcdu.irs.off", Integer.valueOf(i + 1));
                c = C_WHITE;
            } else if (e.insStatus == SystemsMath.INS_READY) {
                st = k("sva.mcdu.irs.nav", Integer.valueOf(i + 1));
            } else if (e.insStatus == SystemsMath.INS_ALIGN && L.afs.irsState == 2) {
                st = k("sva.mcdu.irs.wait", Integer.valueOf(i + 1));
                c = C_AMBER;
            } else if (e.insStatus == SystemsMath.INS_ALIGN) {
                st = k("sva.mcdu.irs.align", Integer.valueOf(i + 1), two(e.insSec));
            } else {
                st = k("sva.mcdu.irs.fault", Integer.valueOf(i + 1));
                c = C_AMBER;
            }
            r.dat(3 + i, false, st, c);
        }
        r.dat(6, false, k("sva.mcdu.back"), C_WHITE);
        if (e.autoIrs) {
            r.dat(6, true, k("sva.mcdu.irs.auto"), C_GREEN);
        } else if (f.irsDone) {
            r.dat(6, true, k("sva.mcdu.irs.done"), C_GREEN);
        } else {
            r.dat(6, true, k("sva.mcdu.irs.align.do"), C_AMBER);
        }
    }

    /** The clock "HHMM" of a world time (ticks; 0 = 06:00). */
    public static String clock(double t) {
        long tod = ((long) Math.floor(t) % 24000 + 24000) % 24000;
        int hh = (int) ((tod / 1000 + 6) % 24), mm = (int) (tod % 1000 * 60 / 1000);
        return (hh < 10 ? "0" : "") + hh + (mm < 10 ? "0" : "") + mm;
    }

    private void pFpln(R r, LinerFms f, Side s, Env e) {
        boolean tmpy = f.tmp != null;
        r.title(k(tmpy ? "sva.mcdu.fpln.tmpy" : "sva.mcdu.fpln.title"), tmpy ? C_YELLOW : C_WHITE);
        if (f.flt.length() > 0) {
            r.sc.add(0, COLS, 1, C_WHITE, true, f.flt);
        }
        if (!f.hasPlan() && !tmpy) {
            r.dat(1, false, k("sva.mcdu.fpln.empty"), C_WHITE);
            return;
        }
        List<LinerFms.Wp> l = f.shown();
        int act = f.shownActive();
        List<Line> ls = lines(f);
        s.scroll = Math.max(0, Math.min(s.scroll, Math.max(0, ls.size() - 1)));
        for (int k = 1; k <= 5; k++) {
            int i = s.scroll + k - 1;
            if (i >= ls.size()) {
                break;
            }
            Line ln = ls.get(i);
            if (ln.kind == LN_END) {
                r.datAt(k, COLS / 2, 2, k("sva.mcdu.fpln.end"), C_WHITE, false);
                continue;
            }
            if (ln.kind == LN_TC || ln.kind == LN_TD) {
                boolean c = ln.kind == LN_TC;
                double sv = c ? f.sTc : f.sTd;
                double ticks = f.sAc < sv ? (sv - f.sAc) / (LinerFms.crzSpd(f.ciUsed()) / 72.0) : 0;
                r.datAt(k, 0, 0, c ? "(T/C)" : "(T/D)", C_GREEN, false);
                r.datAt(k, 8, 0, clock(e.time + ticks), C_GREEN, false);
                r.datAt(k, COLS, 1, (c ? LinerFms.clbSpd(f.ciUsed()) : LinerFms.desSpd(f.ciUsed())) + "/" + pad(f.crz, 4), C_GREEN, false);
                continue;
            }
            LinerFms.Wp w = l.get(ln.idx);
            if (w.kind == LinerFms.K_DISC) {
                r.datAt(k, COLS / 2, 2, k("sva.mcdu.fpln.disc"), C_WHITE, false);
                continue;
            }
            int col = tmpy ? C_YELLOW : ln.idx == act ? C_WHITE : C_GREEN;
            // the label line: the leg's course and length into it (the FROM entry: «ОТ»; the missed approach)
            if (ln.idx == act - 1 || ln.idx == 0 && w.kind == LinerFms.K_ORIG) {
                r.lbl(k, false, k("sva.mcdu.fpln.from"));
            } else {
                LinerFms.Wp prev = prevPoint(l, ln.idx);
                if (prev != null) {
                    double crs = RouteMath.course(prev.x, prev.z, w.x, w.z);
                    double d = Math.sqrt(sq(w.x - prev.x) + sq(w.z - prev.z));
                    if (w.kind == LinerFms.K_MISSED) {
                        r.lblAt(k, 0, 0, k("sva.mcdu.fpln.missed"), C_CYAN);   // the missed approach (the A320's blue)
                    } else {
                        r.lblAt(k, 0, 0, k("sva.mcdu.fpln.crs", three(crs)), C_WHITE);
                        r.lblAt(k, 18, 1, k("sva.mcdu.km", km(d)), C_WHITE);
                    }
                }
            }
            r.datAt(k, 0, 0, w.id + (w.ovfy ? "^" : ""), col, false);
            if (!tmpy && ln.idx >= act && w.pTicks >= 0) {
                r.datAt(k, 8, 0, clock(e.time + w.pTicks), C_GREEN, false);
            }
            // speed / altitude: the constraints magenta, the predictions green
            String spd = w.spd > 0 ? String.valueOf(w.spd) : w.kind == LinerFms.K_ORIG || tmpy ? "---" : String.valueOf((int) Math.round(w.pSpd));
            r.datAt(k, 18, 1, spd + "/", w.spd > 0 ? C_MAGENTA : tmpy ? C_YELLOW : C_GREEN, false);
            String alt;
            int ac;
            if (w.cst != LinerFms.C_NONE) {
                alt = (w.cst == LinerFms.C_ABOVE ? "+" : w.cst == LinerFms.C_BELOW ? "-" : " ") + w.alt;
                ac = C_MAGENTA;
            } else if (w.kind == LinerFms.K_ORIG || w.kind == LinerFms.K_DEST) {
                alt = String.valueOf(w.elev);
                ac = col;
            } else {
                alt = tmpy || Double.isNaN(w.pAlt) || f.crz <= 0 ? "-----" : String.valueOf((int) Math.round(w.pAlt));
                ac = tmpy ? C_YELLOW : C_GREEN;
            }
            r.datAt(k, COLS, 1, alt, ac, false);
        }
        // LSK 6: the destination's summary, or the TMPY's СТЕРЕТЬ / ВСТАВИТЬ
        if (tmpy) {
            r.dat(6, false, k("sva.mcdu.tmpy.erase"), C_AMBER);
            r.dat(6, true, k("sva.mcdu.tmpy.insert"), C_AMBER);
        } else {
            LinerFms.Wp d = f.dest();
            r.lblAt(6, 0, 0, k("sva.mcdu.fpln.destHdr"), C_WHITE);
            if (d != null) {
                r.datAt(6, 0, 0, d.id, C_WHITE, false);
                r.datAt(6, 8, 0, Double.isNaN(f.destTicks) ? "----" : clock(e.time + f.destTicks), C_GREEN, false);
                double dist = f.distToDest();
                r.datAt(6, 18, 1, Double.isNaN(dist) ? "---" : km(dist), C_GREEN, false);
                r.datAt(6, COLS, 1, Double.isNaN(f.destFuel) ? "--,-" : t1((int) Math.round(f.destFuel / 100.0)), C_GREEN, false);
            }
        }
    }

    static LinerFms.Wp prevPoint(List<LinerFms.Wp> l, int i) {
        for (int k = i - 1; k >= 0; k--) {
            if (l.get(k).point()) {
                return l.get(k);
            }
        }
        return null;
    }

    private void pLatRev(R r, LinerFms f, Side s, Env e) {
        int i = selIndex(f, s);
        if (i < 0) {
            r.title(k("sva.mcdu.latrev.gone"), C_WHITE);
            r.dat(6, false, k("sva.mcdu.back"), C_WHITE);
            return;
        }
        LinerFms.Wp w = f.shown().get(i);
        r.title(k("sva.mcdu.latrev.title", w.id), C_WHITE);
        r.lblAt(1, COLS / 2, 2, "X" + (int) Math.floor(w.x) + "/Z" + (int) Math.floor(w.z), C_GREEN);
        if (w.kind != LinerFms.K_ORIG) {
            r.dat(1, false, k("sva.mcdu.latrev.dir"), C_WHITE);
        }
        if (w.kind == LinerFms.K_DEST) {
            r.lbl(1, true, k("sva.mcdu.latrev.rwy"));
            r.dat(1, true, w.rwy.length() > 0 ? w.rwy : "[ ]", C_CYAN);
        }
        if (w.kind != LinerFms.K_DEST && w.kind != LinerFms.K_MISSED) {
            r.lbl(3, true, k("sva.mcdu.latrev.next"));
            r.dat(3, true, "[     ]", C_CYAN);
        }
        r.dat(6, false, k("sva.mcdu.back"), C_WHITE);
    }

    private void pVertRev(R r, LinerFms f, Side s) {
        int i = selIndex(f, s);
        if (i < 0) {
            r.title(k("sva.mcdu.latrev.gone"), C_WHITE);
            r.dat(6, false, k("sva.mcdu.back"), C_WHITE);
            return;
        }
        LinerFms.Wp w = f.shown().get(i);
        r.title(k("sva.mcdu.vertrev.title", w.id), C_WHITE);
        r.lbl(2, false, k("sva.mcdu.vertrev.spd"));
        r.dat(2, false, w.spd > 0 ? String.valueOf(w.spd) : "[ ]", w.spd > 0 ? C_MAGENTA : C_CYAN);
        r.lbl(3, false, k("sva.mcdu.vertrev.alt"));
        r.dat(3, false, w.cst != LinerFms.C_NONE ? (w.cst == LinerFms.C_ABOVE ? "+" : w.cst == LinerFms.C_BELOW ? "-" : "") + w.alt : "[   ]",
                w.cst != LinerFms.C_NONE ? C_MAGENTA : C_CYAN);
        r.lbl(2, true, k("sva.mcdu.vertrev.pred"));
        if (f.tmp == null && !Double.isNaN(w.pAlt) && f.crz > 0) {
            r.dat(2, true, (int) Math.round(w.pSpd) + "/" + (int) Math.round(w.pAlt), C_GREEN);
        }
        r.lbl(5, false, k("sva.mcdu.vertrev.hint"));
        r.dat(6, false, k("sva.mcdu.back"), C_WHITE);
    }

    private void pDir(R r, LinerFms f, Side s, Env e) {
        r.title(k("sva.mcdu.dir.title"), C_WHITE);
        r.lbl(1, false, k("sva.mcdu.dir.wpt"));
        LinerFms.Wp target = null;
        if (f.tmp != null && f.tmpActive < f.tmp.size() && f.tmp.get(f.tmpActive).id.equals(s.dirId)) {
            target = f.tmp.get(f.tmpActive);
        }
        r.dat(1, false, s.dirId.length() > 0 ? s.dirId : "[     ]", f.tmp != null ? C_YELLOW : C_CYAN);
        r.lbl(1, true, k("sva.mcdu.dir.brgDist"));
        if (target != null) {
            double brg = RouteMath.course(e.x, e.z, target.x, target.z);
            r.dat(1, true, three(brg) + "  " + km(Math.sqrt(sq(target.x - e.x) + sq(target.z - e.z))), C_GREEN);
        }
        r.lbl(2, false, k("sva.mcdu.dir.fromPlan"));
        List<Integer> d = dirList(f);
        s.scroll = Math.max(0, Math.min(s.scroll, Math.max(0, d.size() - 1)));
        for (int k = 2; k <= 5; k++) {
            int i = s.scroll + k - 2;
            if (i < d.size()) {
                r.dat(k, false, "<" + f.pts.get(d.get(i).intValue()).id, C_CYAN);
            }
        }
        if (f.tmp != null) {
            r.dat(6, false, k("sva.mcdu.dir.cancel"), C_AMBER);
            r.dat(6, true, k("sva.mcdu.tmpy.insert"), C_AMBER);
        }
    }

    private void pPerfTo(R r, LinerSystems L, LinerFms f, Env e) {
        r.title(k("sva.mcdu.perf.to"), f.phase == LinerFms.PH_TO ? C_GREEN : C_WHITE);
        String[] lb = {"V1", "VR", "V2"};
        int[] v = {f.v1, f.vr, f.v2};
        for (int i = 0; i < 3; i++) {
            r.lbl(i + 1, false, lb[i]);
            if (v[i] > 0) {
                r.dat(i + 1, false, String.valueOf(v[i]), C_CYAN);
            } else {
                r.box(i + 1, false, 3, 0);
            }
        }
        // the flaps / slats retraction speeds and the green dot (the clean configuration's)
        double gw = f.tow(e.riders, e.fob) * 1000;
        r.lblAt(1, 12, 2, k("sva.mcdu.perf.retract"), C_WHITE);
        r.datAt(1, 12, 2, "F=" + speedOf(2, gw), C_GREEN, true);
        r.datAt(2, 12, 2, "S=" + speedOf(1, gw), C_GREEN, true);
        r.datAt(3, 12, 2, "O=" + speedOf(0, gw), C_GREEN, true);
        LinerFms.Wp o = f.origin();
        r.lbl(1, true, k("sva.mcdu.perf.rwy"));
        r.dat(1, true, o != null && o.rwy.length() > 0 ? o.rwy : "--", C_GREEN);
        NavDb.Airport a = o == null ? null : e.db.airport(f.from);
        NavDb.Rwy rw = a == null ? null : a.rwy(o.rwy);
        r.lbl(2, true, k("sva.mcdu.perf.len"));
        r.dat(2, true, rw == null ? "---" : k("sva.mcdu.m", Integer.valueOf(rw.len)), C_GREEN);
        r.lbl(3, true, k("sva.mcdu.perf.flapsThs"));
        r.dat(3, true, k(f.ths10 >= 0 ? "sva.mcdu.perf.thsUp" : "sva.mcdu.perf.thsDn", f.toFlaps > 0 ? String.valueOf(f.toFlaps) : "[]",
                t1(Math.abs(f.ths10))), C_CYAN);
        r.lbl(4, false, k("sva.mcdu.perf.thrAcc"));
        int tr = f.thrRed > 0 ? f.thrRed : e.accDefault, ac = f.acc > 0 ? f.acc : e.accDefault;
        r.dat(4, false, k("sva.mcdu.mm", Integer.valueOf(tr), Integer.valueOf(ac)), f.thrRed > 0 || f.acc > 0 ? C_CYAN : C_GREEN);
        r.lbl(4, true, k("sva.mcdu.perf.flex"));
        r.dat(4, true, f.flex > 0 ? String.valueOf(f.flex) : "[ ]", C_CYAN);
        r.lbl(5, true, k("sva.mcdu.perf.vspd"));
        r.dat(5, true, k("sva.mcdu.perf.compute"), C_CYAN);
        r.dat(6, true, k("sva.mcdu.next"), C_WHITE);
    }

    /** A configuration's retraction speed (km/h): 1,2 x its stall at the mass; 0 - the clean green dot. */
    static int speedOf(int flaps, double mass) {
        PlaneParams p = HighLiftMath.params(HighLiftMath.LINER, VehicleType.LINER.plane, flaps, flaps > 0, false, false);
        return (int) Math.round(LinerAfs.vls(p.stall, mass) * 72 + (flaps == 0 ? 15 : 0));
    }

    private void pPerfPhase(R r, LinerSystems L, LinerFms f, Side s, Env e) {
        int p = s.page;
        int ph = p == P_PERF_CLB ? LinerFms.PH_CLB : p == P_PERF_CRZ ? LinerFms.PH_CRZ : LinerFms.PH_DES;
        r.title(k("sva.mcdu.perf." + LinerFms.PH_KEYS[ph]), f.phase == ph ? C_GREEN : C_WHITE);
        r.lbl(1, false, k("sva.mcdu.init.ci"));
        r.dat(1, false, f.ci >= 0 ? String.valueOf(f.ci) : "50", f.ci >= 0 ? C_CYAN : C_GREEN);
        int ciU = f.ciUsed();
        int man = ph == LinerFms.PH_CLB ? LinerFms.clbSpd(ciU) : ph == LinerFms.PH_CRZ ? LinerFms.crzSpd(ciU) : LinerFms.desSpd(ciU);
        r.lbl(2, false, k("sva.mcdu.perf.managed"));
        r.dat(2, false, String.valueOf(man), f.phase == ph && L.afs.spdMan ? C_MAGENTA : C_GREEN);
        r.lbl(3, false, k("sva.mcdu.perf.selected"));
        r.dat(3, false, f.phase == ph && !L.afs.spdMan ? String.valueOf(L.afs.spd) : "---", C_CYAN);
        r.lbl(1, true, k("sva.mcdu.init.crzAlt"));
        r.dat(1, true, f.crz > 0 ? String.valueOf(f.crz) : "---", C_CYAN);
        double v = man / 72.0;
        if (ph == LinerFms.PH_CLB) {
            r.lbl(2, true, k("sva.mcdu.perf.toTc"));
            double d = Double.isNaN(f.sTc) ? Double.NaN : Math.max(0, f.sTc - f.sAc);
            r.dat(2, true, Double.isNaN(d) ? "----" : hm(d / v) + " " + km(d), C_GREEN);
        } else if (ph == LinerFms.PH_CRZ) {
            r.lbl(2, true, k("sva.mcdu.perf.toTd"));
            double d = Double.isNaN(f.sTd) ? Double.NaN : Math.max(0, f.sTd - f.sAc);
            r.dat(2, true, Double.isNaN(d) ? "----" : hm(d / v) + " " + km(d), C_GREEN);
        } else {
            r.lbl(2, true, k("sva.mcdu.perf.td"));
            double d = Double.isNaN(f.sTd) ? Double.NaN : Math.max(0, f.sTd - f.sAc);
            r.dat(2, true, Double.isNaN(d) ? "----" : hm(d / v) + " " + km(d), C_GREEN);
            r.lbl(3, true, k("sva.mcdu.perf.path"));
            r.dat(3, true, t1((int) Math.round(LinerFms.DES_ANGLE * 10)), C_GREEN);
            r.lbl(4, true, k("sva.mcdu.perf.toDest"));
            double dd = f.distToDest();
            r.dat(4, true, Double.isNaN(dd) ? "---" : km(dd), C_GREEN);
        }
        if (ph != LinerFms.PH_CLB) {
            apprActivation(r, f, s, e);
        }
        r.dat(6, false, k("sva.mcdu.prev"), C_WHITE);
        r.dat(6, true, k("sva.mcdu.next"), C_WHITE);
    }

    private void apprActivation(R r, LinerFms f, Side s, Env e) {
        if (f.apprActive) {
            r.lbl(5, false, k("sva.mcdu.perf.apprOn"));
        } else if (!e.ground) {
            r.dat(5, false, k(s.confirm > 0 ? "sva.mcdu.perf.apprConfirm" : "sva.mcdu.perf.apprActivate"), s.confirm > 0 ? C_AMBER : C_WHITE);
        }
    }

    private void pPerfAppr(R r, LinerFms f, Side s, Env e) {
        r.title(k("sva.mcdu.perf.appr"), f.phase == LinerFms.PH_APPR ? C_GREEN : C_WHITE);
        r.lbl(1, false, "QNH");
        if (f.qnh > 0) {
            r.dat(1, false, String.valueOf(f.qnh), C_CYAN);
        } else {
            r.box(1, false, 4, 0);
        }
        r.lbl(2, false, k("sva.mcdu.perf.temp"));
        r.dat(2, false, f.apprTemp == LinerFms.NO_TEMP ? "[ ]" : signed(f.apprTemp), C_CYAN);
        r.lbl(3, false, k("sva.mcdu.perf.wind"));
        r.dat(3, false, f.windDir < 0 ? "[ ]/[ ]" : three(f.windDir) + "/" + f.windSpd, C_CYAN);
        double lw = e.gw;
        int vls = (int) Math.round(f.vlsLdg(lw));
        r.lbl(4, false, k("sva.mcdu.perf.vappVls"));
        r.dat(4, false, String.valueOf(f.vappKmh(lw)), f.vapp > 0 ? C_CYAN : C_GREEN);
        r.datAt(4, 6, 0, String.valueOf(vls), C_GREEN, false);
        LinerFms.Wp d = f.dest();
        r.lbl(1, true, k("sva.mcdu.perf.ldgRwy"));
        r.dat(1, true, d == null ? "----" : d.id, C_GREEN);
        r.lbl(2, true, k("sva.mcdu.perf.dh"));
        r.dat(2, true, f.dh >= 0 ? String.valueOf(f.dh) : "[ ]", C_CYAN);
        r.lbl(3, true, k("sva.mcdu.perf.mda"));
        r.dat(3, true, f.mda >= 0 ? String.valueOf(f.mda) : "[ ]", C_CYAN);
        r.lbl(4, true, k("sva.mcdu.perf.ldgConf"));
        r.dat(4, true, k(f.ldgConf == 3 ? "sva.mcdu.perf.conf3" : "sva.mcdu.perf.full"), C_CYAN);
        apprActivation(r, f, s, e);
        r.dat(6, false, k("sva.mcdu.prev"), C_WHITE);
        r.dat(6, true, k("sva.mcdu.next"), C_WHITE);
    }

    private void pPerfGa(R r, LinerFms f, Env e) {
        r.title(k("sva.mcdu.perf.ga"), f.phase == LinerFms.PH_GA ? C_GREEN : C_WHITE);
        r.lbl(5, false, k("sva.mcdu.perf.thrAcc"));
        int tr = f.gaThrRed > 0 ? f.gaThrRed : e.accDefault, ac = f.gaAcc > 0 ? f.gaAcc : e.accDefault;
        r.dat(5, false, k("sva.mcdu.mm", Integer.valueOf(tr), Integer.valueOf(ac)), f.gaThrRed > 0 || f.gaAcc > 0 ? C_CYAN : C_GREEN);
        r.dat(6, false, k("sva.mcdu.prev"), C_WHITE);
    }

    private void pFuel(R r, LinerFms f, Env e) {
        r.title(k("sva.mcdu.fuel.title"), C_WHITE);
        LinerFms.Wp d = f.dest();
        r.lblAt(1, 0, 0, k("sva.mcdu.fuel.dest"), C_WHITE);
        r.lblAt(1, 9, 0, k("sva.mcdu.fuel.time"), C_WHITE);
        r.lblAt(1, COLS, 1, k("sva.mcdu.fuel.efob"), C_WHITE);
        r.datAt(1, 0, 0, d == null ? "----" : f.to, C_GREEN, false);
        r.datAt(1, 9, 0, Double.isNaN(f.destTicks) ? "----" : clock(e.time + f.destTicks), C_GREEN, false);
        r.datAt(1, COLS, 1, Double.isNaN(f.destFuel) ? "---" : group((int) Math.round(f.destFuel)), C_GREEN, false);
        double altn = f.altnFuel(e.db, e.burn);
        r.lbl(2, false, k("sva.mcdu.init.altn"));
        r.dat(2, false, f.altn.length() > 0 ? f.altn : k("sva.mcdu.none"), C_GREEN);
        if (f.altn.length() > 0 && !Double.isNaN(f.destFuel)) {
            r.dat(2, true, group((int) Math.round(f.destFuel - altn)), C_GREEN);
        }
        r.lbl(3, false, k("sva.mcdu.fuel.gw"));
        r.dat(3, false, t1((int) Math.round(e.gw / 100.0)), C_GREEN);
        r.lbl(3, true, k("sva.mcdu.fuel.fob"));
        r.dat(3, true, group((int) Math.round(e.fob)), C_GREEN);
        r.lbl(4, false, k("sva.mcdu.initb.rsv"));
        r.dat(4, false, group(f.reserve), C_CYAN);
        double min = f.reserve + altn;
        r.lbl(5, false, k("sva.mcdu.fuel.minDest"));
        r.dat(5, false, group((int) Math.round(min)), C_CYAN);
        r.lbl(6, false, k("sva.mcdu.fuel.extra"));
        double extra = Double.isNaN(f.destFuel) ? Double.NaN : f.destFuel - min;
        r.dat(6, false, Double.isNaN(extra) ? "---" : group((int) Math.round(extra)), !Double.isNaN(extra) && extra < 0 ? C_AMBER : C_GREEN);
    }

    private void pProg(R r, LinerFms f, Side s, Env e) {
        r.title(k("sva.mcdu.prog." + LinerFms.PH_KEYS[f.phase]), C_GREEN);
        r.lblAt(1, 0, 0, k("sva.mcdu.prog.crzLbl"), C_WHITE);
        r.lblAt(1, 10, 0, k("sva.mcdu.prog.opt"), C_WHITE);
        r.lblAt(1, COLS, 1, k("sva.mcdu.prog.max"), C_WHITE);
        r.datAt(1, 0, 0, f.crz > 0 ? String.valueOf(f.crz) : "---", C_CYAN, false);
        r.datAt(1, 10, 0, String.valueOf(LinerFms.optAlt(e.gw)), C_GREEN, false);
        r.datAt(1, COLS, 1, String.valueOf(LinerFms.maxAlt(e.gw)), C_MAGENTA, false);
        r.lbl(2, true, k("sva.mcdu.perf.toDest"));
        double dd = f.distToDest();
        r.dat(2, true, Double.isNaN(dd) ? "---" : k("sva.mcdu.km", km(dd)), C_GREEN);
        r.lbl(3, false, k("sva.mcdu.prog.brgDist"));
        r.dat(3, false, k("sva.mcdu.prog.toWpt", s.progTo.length() > 0 ? s.progTo : "[    ]"), C_CYAN);
        double[] t = progTarget(f, s, e);
        if (t != null) {
            double brg = RouteMath.course(e.x, e.z, t[0], t[1]);
            r.dat(3, true, three(brg) + "/ " + km(Math.sqrt(sq(t[0] - e.x) + sq(t[1] - e.z))), C_GREEN);
        }
        r.lblAt(4, 0, 0, k("sva.mcdu.prog.acc"), C_WHITE);
        r.lblAt(4, 14, 1, k("sva.mcdu.prog.req"), C_WHITE);
        r.lblAt(4, COLS, 1, k("sva.mcdu.prog.est"), C_WHITE);
        r.datAt(4, 0, 0, k("sva.mcdu.prog.high"), C_GREEN, false);
        r.datAt(4, 14, 1, "0,2", C_CYAN, false);
        r.datAt(4, COLS, 1, "0,02", C_GREEN, false);
        r.dat(5, false, "GPS PRIMARY", C_GREEN);
        String lbl;
        double d;
        if (f.phase <= LinerFms.PH_CLB) {
            lbl = "sva.mcdu.prog.toTc";
            d = Double.isNaN(f.sTc) ? Double.NaN : Math.max(0, f.sTc - f.sAc);
        } else if (f.phase == LinerFms.PH_CRZ) {
            lbl = "sva.mcdu.prog.toTd";
            d = Double.isNaN(f.sTd) ? Double.NaN : Math.max(0, f.sTd - f.sAc);
        } else {
            lbl = "sva.mcdu.perf.toDest";
            d = dd;
        }
        r.lbl(6, false, k(lbl));
        if (!Double.isNaN(d)) {
            r.dat(6, false, k("sva.mcdu.kmTime", km(d), hm(d / Math.max(0.5, e.gs))), C_GREEN);
        }
    }

    static double[] progTarget(LinerFms f, Side s, Env e) {
        if (s.progTo.length() == 0) {
            return null;
        }
        int[] c = coords(s.progTo);
        if (c != null) {
            return new double[] {c[0] + 0.5, c[1] + 0.5};
        }
        NavDb.Wpt w = e.db.wpt(s.progTo);
        if (w != null) {
            return new double[] {w.x, w.z};
        }
        NavDb.Airport a = e.db.airport(s.progTo);
        if (a != null) {
            return new double[] {a.x, a.z};
        }
        for (LinerFms.Wp p : f.pts) {
            if (p.point() && p.id.equals(s.progTo)) {
                return new double[] {p.x, p.z};
            }
        }
        return null;
    }

    private void pRad(R r, LinerSystems L, Env e) {
        r.title(k("sva.mcdu.rad.title"), C_WHITE);
        LinerAfs F = L.afs;
        LinerIls.Station st = F.ils;
        boolean manual = F.ilsChosen != null && F.ilsChosen.length() > 0;
        r.lbl(1, false, k("sva.mcdu.rad.ils"));
        r.lbl(1, true, k("sva.mcdu.rad.crs"));
        if (st != null) {
            r.dat(1, false, st.ident + "/" + LinerIls.freqText(st.freq).replace('.', ','), manual ? C_CYAN : C_GREEN);
            r.dat(1, true, three(st.course), C_GREEN);
            LinerIls.Dev d = LinerIls.dev(st, e.x, e.y, e.z, 1e9);
            r.lbl(2, false, k("sva.mcdu.rad.src"));
            r.dat(2, false, k("sva.mcdu.rad.beacon"), C_GREEN);
            r.lbl(3, false, k("sva.mcdu.rad.dist"));
            r.dat(3, false, k("sva.mcdu.km", km(Math.sqrt(sq(st.tx() - e.x) + sq(st.tz() - e.z)))), C_GREEN);
            r.lbl(3, true, k("sva.mcdu.rad.gs"));
            r.dat(3, true, t1((int) Math.round(st.angle * 10)), C_GREEN);
            r.lbl(4, false, k("sva.mcdu.rad.elev"));
            r.dat(4, false, k("sva.mcdu.m", Integer.valueOf((int) Math.round(st.elev))), C_GREEN);
            r.lbl(4, true, k("sva.mcdu.rad.signal"));
            r.dat(4, true, k(d.locValid ? "sva.mcdu.rad.yes" : "sva.mcdu.rad.no"), d.locValid ? C_GREEN : C_AMBER);
        } else if (manual) {
            r.dat(1, false, k("sva.mcdu.rad.waiting"), C_CYAN);
        } else {
            r.dat(1, false, "[    ]/[    ]", C_CYAN);
        }
        r.lbl(5, false, k("sva.mcdu.rad.tune"));
        boolean plan = !manual && st != null && F.ilsPlan.length() > 0 && F.ilsPlan.equals(st.key());
        r.dat(5, false, k(manual ? "sva.mcdu.rad.manual" : plan ? "sva.mcdu.rad.plan" : "sva.mcdu.rad.auto"), manual ? C_CYAN : C_GREEN);
        r.lbl(5, true, "VOR/ADF");
        r.dat(5, true, "---", C_WHITE);
        r.dat(6, false, k("sva.mcdu.rad.list"), C_WHITE);
    }

    private void pIlsList(R r, LinerSystems L, Side s, Env e) {
        r.title(k("sva.mcdu.ils.title"), C_WHITE);
        List<LinerIls.Station> st = nearIls(e);
        s.scroll = Math.max(0, Math.min(s.scroll, Math.max(0, st.size() - 1)));
        if (st.isEmpty()) {
            r.dat(1, false, k("sva.mcdu.ils.none"), C_WHITE);
        }
        for (int k = 1; k <= 5; k++) {
            int i = s.scroll + k - 1;
            if (i >= st.size()) {
                break;
            }
            LinerIls.Station o = st.get(i);
            boolean on = o.key().equals(L.afs.ilsChosen);
            r.dat(k, false, "<" + o.ident + " " + LinerIls.freqText(o.freq).replace('.', ','), on ? C_CYAN : C_GREEN);
            r.dat(k, true, three(o.course) + " " + km(Math.sqrt(sq(o.tx() - e.x) + sq(o.tz() - e.z))), C_GREEN);
        }
        r.dat(6, false, k("sva.mcdu.back"), C_WHITE);
    }

    private void pData(R r, Env e) {
        r.title(k("sva.mcdu.data.title"), C_WHITE);
        r.lbl(1, false, k("sva.mcdu.data.posLbl"));
        r.dat(1, false, k("sva.mcdu.data.pos"), C_WHITE);
        r.lbl(1, true, k("sva.mcdu.data.db"));
        r.dat(1, true, k("sva.mcdu.data.apts"), C_WHITE);
        r.dat(2, true, k("sva.mcdu.data.wpts"), C_WHITE);
        r.dat(3, true, k("sva.mcdu.data.ils"), C_WHITE);
        r.lbl(6, false, k("sva.mcdu.data.count"));
        r.dat(6, false, k("sva.mcdu.data.counts", Integer.valueOf(e.db.airports.size()), Integer.valueOf(e.db.wpts.size()),
                Integer.valueOf(e.db.ils.size())), C_GREEN);
    }

    private void pAptList(R r, Side s, Env e) {
        r.title(k("sva.mcdu.apt.title"), C_WHITE);
        List<NavDb.Airport> l = e.db.airports;
        s.scroll = Math.max(0, Math.min(s.scroll, Math.max(0, l.size() - 1)));
        if (l.isEmpty()) {
            r.dat(1, false, k("sva.mcdu.apt.none"), C_WHITE);
        }
        for (int k = 1; k <= 5; k++) {
            int i = s.scroll + k - 1;
            if (i >= l.size()) {
                break;
            }
            NavDb.Airport a = l.get(i);
            NavDb.Rwy rw = a.rwy("");
            r.lbl(k, false, k("sva.mcdu.src." + NavDb.SRC_KEYS[a.src]));
            r.dat(k, false, "<" + a.ident, a.src == NavDb.SRC_PILOT ? C_CYAN : C_GREEN);
            r.dat(k, true, (rw == null ? "" : rw.id) + (rw != null && rw.ilsKey.length() > 0 ? " ILS " + rw.ilsIdent : ""), C_GREEN);
        }
        r.lbl(6, false, k("sva.mcdu.apt.new"));
        r.dat(6, false, "[      ]", C_CYAN);
        r.dat(6, true, k("sva.mcdu.backR"), C_WHITE);
    }

    private void pApt(R r, Side s, Env e) {
        NavDb.Airport a = e.db.airport(s.selId);
        if (a == null) {
            r.title(k("sva.mcdu.latrev.gone"), C_WHITE);
            r.dat(6, true, k("sva.mcdu.backR"), C_WHITE);
            return;
        }
        r.title(a.ident, C_WHITE);
        r.lbl(1, false, "X/Z");
        r.dat(1, false, (int) Math.floor(a.x) + "/" + (int) Math.floor(a.z), C_GREEN);
        r.lbl(1, true, k("sva.mcdu.apt.elev"));
        r.dat(1, true, k("sva.mcdu.m", Integer.valueOf(a.elev)), C_GREEN);
        r.lbl(2, false, k("sva.mcdu.apt.src"));
        r.dat(2, false, k("sva.mcdu.src." + NavDb.SRC_KEYS[a.src]), C_GREEN);
        for (int i = 0; i < Math.min(3, a.rwys.size()); i++) {
            NavDb.Rwy rw = a.rwys.get(i);
            r.lbl(3 + i, false, k("sva.mcdu.apt.rwy", rw.id));
            r.dat(3 + i, false, three(rw.hdg) + "  " + k("sva.mcdu.m", Integer.valueOf(rw.len)), C_GREEN);
            if (rw.ilsKey.length() > 0) {
                r.dat(3 + i, true, rw.ilsIdent + " " + LinerIls.freqText(rw.freq).replace('.', ','), C_GREEN);
            }
        }
        r.dat(6, true, k("sva.mcdu.backR"), C_WHITE);
    }

    private void pWptList(R r, Side s, Env e) {
        r.title(k("sva.mcdu.wpt.title"), C_WHITE);
        List<NavDb.Wpt> l = e.db.wpts;
        s.scroll = Math.max(0, Math.min(s.scroll, Math.max(0, l.size() - 1)));
        if (l.isEmpty()) {
            r.dat(1, false, k("sva.mcdu.wpt.none"), C_WHITE);
        }
        for (int k = 1; k <= 5; k++) {
            int i = s.scroll + k - 1;
            if (i >= l.size()) {
                break;
            }
            NavDb.Wpt w = l.get(i);
            r.lbl(k, false, k("sva.mcdu.src." + NavDb.SRC_KEYS[w.src]));
            r.dat(k, false, w.ident, w.src == NavDb.SRC_PILOT ? C_CYAN : C_GREEN);
            r.dat(k, true, (int) Math.floor(w.x) + "/" + (int) Math.floor(w.z), C_GREEN);
        }
        r.lbl(6, false, k("sva.mcdu.wpt.new"));
        r.dat(6, false, "[      ]", C_CYAN);
        r.dat(6, true, k("sva.mcdu.backR"), C_WHITE);
    }

    private void pPos(R r, Env e) {
        r.title(k("sva.mcdu.pos.title"), C_WHITE);
        r.lbl(1, false, k("sva.mcdu.pos.gps"));
        r.dat(1, false, (int) Math.floor(e.x) + "/" + (int) Math.floor(e.z), C_GREEN);
        r.lbl(2, false, k("sva.mcdu.pos.ins"));
        r.dat(2, false, e.insStatus == SystemsMath.INS_READY ? (int) Math.floor(e.x) + "/" + (int) Math.floor(e.z) : "----/----",
                e.insStatus == SystemsMath.INS_READY ? C_GREEN : C_AMBER);
        r.lbl(3, false, k("sva.mcdu.pos.alt"));
        r.dat(3, false, k("sva.mcdu.m", Integer.valueOf((int) Math.round(e.y))), C_GREEN);
        r.lbl(4, false, k("sva.mcdu.pos.gs"));
        r.dat(4, false, k("sva.mcdu.kmh", Integer.valueOf((int) Math.round(e.gs * 72))), C_GREEN);
        r.lbl(5, false, k("sva.mcdu.pos.hdg"));
        r.dat(5, false, three(e.hdg), C_GREEN);
        r.dat(6, true, k("sva.mcdu.backR"), C_WHITE);
    }

    // ---------------------------------------------------------------- formats

    /** "052" (three digits, 360 for north). */
    static String three(double deg) {
        int d = (int) Math.round(LinerIls.wrap360(deg));
        d = d == 0 ? 360 : d;
        return (d < 10 ? "00" : d < 100 ? "0" : "") + d;
    }

    /** Kilometres with one decimal "3,2" (blocks in). */
    static String km(double blocks) {
        return t1((int) Math.round(blocks / 100.0));
    }

    /** Tenths as "18,6". */
    static String t1(int tenths) {
        int a = Math.abs(tenths);
        return (tenths < 0 ? "-" : "") + a / 10 + "," + a % 10;
    }

    /** Thousands grouped "28 600". */
    static String group(int v) {
        String s = String.valueOf(Math.abs(v));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) {
                b.append(' ');
            }
            b.append(s.charAt(i));
        }
        return (v < 0 ? "-" : "") + b;
    }

    /** "H:MM" of ticks in the game's time (a tick is 3,6 game seconds - the clock the ETAs use). */
    static String hm(double ticks) {
        if (Double.isNaN(ticks)) {
            return "-:--";
        }
        int gm = (int) Math.round(Math.max(0, ticks) * 0.06);
        int h = gm / 60, m = gm % 60;
        return h + ":" + (m < 10 ? "0" : "") + m;
    }

    static String signed(int v) {
        return (v >= 0 ? "+" : "") + v;
    }

    static String two(int v) {
        return (v < 10 ? "0" : "") + v;
    }

    static String pad(int v, int n) {
        String s = v < 0 ? "---" : String.valueOf(v);
        while (s.length() < n) {
            s = " " + s;
        }
        return s;
    }

    static double sq(double v) {
        return v * v;
    }
}
