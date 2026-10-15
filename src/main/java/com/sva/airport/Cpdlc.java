package com.sva.airport;

import java.util.ArrayList;
import java.util.List;

import com.sva.vehicle.LinerMcdu;

/**
 * Airport wave B2 (plan-next §9, item 31): CPDLC - the datalink between a tower and an aircraft (the MCDU's «СВЯЗЬ» page,
 * the tower's flight strips). Pure: the message model, its states, the responses, the timeouts and the log; the server
 * (block.AtcServerSVA) keeps one Log per aircraft and one per tower and moves the messages between them.
 * <ul>
 * <li><b>Uplinks</b> (tower -> aircraft): РУЛЕНИЕ К ПОЛОСЕ xx ПО МАРШРУТУ ..., ЗАНИМАЙТЕ ПОЛОСУ xx, ВЗЛЁТ РАЗРЕШАЮ, ПОСАДКУ
 * РАЗРЕШАЮ, УХОДИТЕ НА ВТОРОЙ КРУГ, НАБОР / СНИЖЕНИЕ ДО ..., КУРС ..., ДОЛОЖИТЕ ..., ОЖИДАЙТЕ ПЕРЕД ПОЛОСОЙ, free text; and
 * the tower's answers to a request - ОЖИДАЙТЕ (standby) and НЕ МОГУ / ОТКАЗАНО (unable).</li>
 * <li><b>Downlinks</b> (aircraft -> tower): ГОТОВ К ВЗЛЁТУ, ЗАПРОС ПОСАДКИ, ЗАПРОС НАБОРА ..., ПРОШУ РУЛЕНИЕ, free text, and the
 * pilot's responses ПРИНЯЛ (WILCO) / НЕ МОГУ (UNABLE) / ОЖИДАЙТЕ (STBY).</li>
 * <li><b>States</b>: an uplink that needs a response is OPEN until the crew answers - WILCO / UNABLE close it, STBY holds
 * it open longer; no answer in RESP_TICKS (STBY: STBY_TICKS) - TIMEOUT. A request (downlink) is OPEN until the tower
 * answers with an uplink that refers to it (ref): a clearance closes it, the tower's ОЖИДАЙТЕ makes it STBY, НЕ МОГУ -
 * UNABLE; unanswered in REQ_TICKS - TIMEOUT. The tower's own answers need no response (CLOSED once read).</li>
 * <li><b>Read</b>: an uplink is unread until the crew opens it - the amber «ATC MSG» lamps and the PFD / ND memo stay on
 * while any is unread (a chime when one arrives).</li>
 * <li><b>Load</b> (A320 FANS-like): after ПРИНЯЛ on НАБОР / СНИЖЕНИЕ ДО or КУРС the MCDU offers «ЗАГРУЗИТЬ» - it presets the
 * FCU's altitude or heading window (preset()); the pilot still pulls / pushes the knob.</li>
 * </ul>
 * The text of a message is a lang key per type («sva.cpdlc.u.N» / «sva.cpdlc.d.N») with its arguments.
 */
public final class Cpdlc {

    /** Uplink types. */
    public static final int U_TAXI = 0, U_LINEUP = 1, U_TAKEOFF = 2, U_LAND = 3, U_GOAROUND = 4, U_CLIMB = 5, U_DESCEND = 6, U_HDG = 7,
            U_REPORT = 8, U_FREE = 9, U_STBY = 10, U_UNABLE = 11, U_HOLDSHORT = 12, U_TYPES = 13;
    /** Downlink types (requests; then the pilot's responses). */
    public static final int D_READY = 20, D_LANDING = 21, D_CLIMB = 22, D_TAXI = 23, D_FREE = 24, D_WILCO = 25, D_UNABLE = 26, D_STBY = 27,
            D_FIRST = 20, D_LAST = 27;
    /** The pilot's responses. */
    public static final int R_WILCO = 0, R_UNABLE = 1, R_STBY = 2;
    /** States. */
    public static final int S_OPEN = 0, S_STBY = 1, S_WILCO = 2, S_UNABLE = 3, S_TIMEOUT = 4, S_CLOSED = 5;
    public static final String[] STATE_KEYS = {"open", "stby", "wilco", "unable", "timeout", "closed"};
    /** What ДОЛОЖИТЕ asks for (U_REPORT's argument). */
    public static final int REP_LOC = 0, REP_VACATED = 1, REP_LEVEL = 2, REP_HOLDING = 3, REPORTS = 4;
    /** Timeouts (ticks): a response, a response after STBY, a request's answer. */
    public static final int RESP_TICKS = 1800, STBY_TICKS = 3600, REQ_TICKS = 3600;
    /** A log keeps this many messages; free text at most FREE_MAX characters. */
    public static final int LOG_MAX = 30, FREE_MAX = 40;
    /** FCU presets: the altitude window, the heading window. */
    public static final int P_ALT = 0, P_HDG = 1;

    private Cpdlc() {
    }

    /** One message. */
    public static final class Msg {
        public int id, ref = -1, type, arg, state = S_CLOSED;
        public boolean up, read, loaded;
        /** A runway / a route / free text. */
        public String s = "", s2 = "";
        /** Who: the tower's ICAO code; the aircraft's callsign (the tower's log). */
        public String tower = "", call = "";
        public long sent, closed = -1;
        /** The aircraft's entity id (the tower's log). */
        public int aircraft;

        public Msg copy() {
            Msg m = new Msg();
            m.id = id;
            m.ref = ref;
            m.type = type;
            m.arg = arg;
            m.state = state;
            m.up = up;
            m.read = read;
            m.loaded = loaded;
            m.s = s;
            m.s2 = s2;
            m.tower = tower;
            m.call = call;
            m.sent = sent;
            m.closed = closed;
            m.aircraft = aircraft;
            return m;
        }

        public boolean open() {
            return state == S_OPEN || state == S_STBY;
        }
    }

    /** Does an uplink type want the crew's response (WILCO / UNABLE / STBY)? */
    public static boolean needsResponse(int type) {
        return type >= U_TAXI && type < U_TYPES && type != U_STBY && type != U_UNABLE;
    }

    public static boolean isRequest(int type) {
        return type == D_READY || type == D_LANDING || type == D_CLIMB || type == D_TAXI;
    }

    /** The lang key of a message's text. */
    public static String key(Msg m) {
        if (m.up && m.type == U_TAXI && m.s2.length() > 0) {
            return "sva.cpdlc.taxiRoute";                           // the follow-me route: its length
        }
        return m.up ? "sva.cpdlc.u." + m.type : "sva.cpdlc.d." + (m.type - D_FIRST);
    }

    /** The arguments of its text. */
    public static String[] args(Msg m) {
        switch (m.type) {
            case U_TAXI:
                return m.s2.length() == 0 ? new String[] {m.s} : new String[] {m.s, m.s2};
            case U_LINEUP:
            case U_TAKEOFF:
            case U_LAND:
            case U_HOLDSHORT:
            case D_READY:
            case D_LANDING:
                return new String[] {m.s};
            case U_CLIMB:
            case U_DESCEND:
            case D_CLIMB:
                return new String[] {String.valueOf(m.arg)};
            case U_HDG:
                return new String[] {TowerMath.three(m.arg)};
            case U_REPORT:
                return new String[] {"#sva.cpdlc.rep." + Math.max(0, Math.min(REPORTS - 1, m.arg)), m.s};
            case U_FREE:
            case D_FREE:
                return new String[] {m.s};
            default:
                return new String[0];
        }
    }

    /** The message's text through a resolver (a "#key" argument translated). */
    public static String text(Msg m, LinerMcdu.Resolver r) {
        String[] a = args(m);
        for (int i = 0; i < a.length; i++) {
            if (a[i].startsWith("#")) {
                a[i] = r.tr(a[i].substring(1), new String[0]);
            }
        }
        return r.tr(key(m), a);
    }

    /** Free text: printable, upper case, at most FREE_MAX. */
    public static String cleanFree(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < FREE_MAX; i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if (c >= ' ' && c != '§' && c != '|' && c != '\u0001' && c != '\u0002') {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    /** The FCU preset an uplink allows after WILCO: {P_ALT / P_HDG, value}; null none. */
    public static int[] preset(Msg m) {
        if (m == null || !m.up || m.state != S_WILCO) {
            return null;
        }
        if (m.type == U_CLIMB || m.type == U_DESCEND) {
            return new int[] {P_ALT, m.arg};
        }
        if (m.type == U_HDG) {
            return new int[] {P_HDG, ((m.arg % 360) + 360) % 360};
        }
        return null;
    }

    /** The pilot's response's downlink type. */
    public static int responseType(int r) {
        return r == R_WILCO ? D_WILCO : r == R_UNABLE ? D_UNABLE : D_STBY;
    }

    // ---------------------------------------------------------------- the log

    /** One side's messages (an aircraft's, a tower's): the newest last. */
    public static final class Log {
        public final List<Msg> msgs = new ArrayList<Msg>();
        private int nextId = 1;

        public Msg add(Msg m) {
            msgs.add(m);
            while (msgs.size() > LOG_MAX) {
                // the oldest closed one goes first; all open: the oldest
                int k = 0;
                for (int i = 0; i < msgs.size(); i++) {
                    if (!msgs.get(i).open()) {
                        k = i;
                        break;
                    }
                }
                msgs.remove(k);
            }
            return m;
        }

        /** A new message of this side: its id, the time, the state its type starts with. */
        public Msg make(boolean up, int type, int arg, String s, String s2, int ref, long now) {
            Msg m = new Msg();
            m.id = nextId;
            nextId = nextId % 9999 + 1;
            m.up = up;
            m.type = type;
            m.arg = arg;
            m.s = s == null ? "" : s;
            m.s2 = s2 == null ? "" : s2;
            m.ref = ref;
            m.sent = now;
            m.state = up ? needsResponse(type) ? S_OPEN : S_CLOSED : isRequest(type) ? S_OPEN : S_CLOSED;
            return m;
        }

        public Msg byId(int id) {
            for (Msg m : msgs) {
                if (m.id == id) {
                    return m;
                }
            }
            return null;
        }

        /** The newest uplink still waiting for the crew's response (null none). */
        public Msg pendingUplink() {
            for (int i = msgs.size() - 1; i >= 0; i--) {
                Msg m = msgs.get(i);
                if (m.up && m.open()) {
                    return m;
                }
            }
            return null;
        }

        /** The newest request (downlink) still open (null none). */
        public Msg openRequest(int type) {
            for (int i = msgs.size() - 1; i >= 0; i--) {
                Msg m = msgs.get(i);
                if (!m.up && m.type == type && m.open()) {
                    return m;
                }
            }
            return null;
        }

        public int unread() {
            int n = 0;
            for (Msg m : msgs) {
                n += m.up && !m.read ? 1 : 0;
            }
            return n;
        }

        /** The newest unread uplink (null none). */
        public Msg firstUnread() {
            for (int i = msgs.size() - 1; i >= 0; i--) {
                if (msgs.get(i).up && !msgs.get(i).read) {
                    return msgs.get(i);
                }
            }
            return null;
        }

        public void readAll() {
            for (Msg m : msgs) {
                m.read = true;
            }
        }

        /**
         * The crew answers uplink `id`: WILCO / UNABLE close it, STBY keeps it open (a longer timeout). @return the response
         * (a downlink referring to it) for the tower, null when it can't be answered (unknown, closed, needs none).
         */
        public Msg respond(int id, int r, long now) {
            Msg m = byId(id);
            if (m == null || !m.up || !m.open() || !needsResponse(m.type) || r < R_WILCO || r > R_STBY) {
                return null;
            }
            if (r == R_STBY && m.state == S_STBY) {
                return null;                                        // standby once
            }
            m.read = true;
            m.state = r == R_WILCO ? S_WILCO : r == R_UNABLE ? S_UNABLE : S_STBY;
            if (r == R_STBY) {
                m.sent = now;                                        // the longer clock from now
            } else {
                m.closed = now;
            }
            Msg d = make(false, responseType(r), 0, "", "", m.id, now);
            d.tower = m.tower;
            d.call = m.call;
            d.aircraft = m.aircraft;
            return add(d);
        }

        /**
         * The tower's uplink answers a request of this aircraft (`ref` its id): a clearance closes it, U_STBY - STBY, U_UNABLE
         * - UNABLE. Used on the aircraft's log when the uplink arrives (and on the tower's for its copy).
         */
        public void answered(int ref, int upType, long now) {
            Msg m = byId(ref);
            if (m == null || m.up || !m.open()) {
                return;
            }
            if (upType == U_STBY) {
                m.state = S_STBY;
                m.sent = now;
            } else {
                m.state = upType == U_UNABLE ? S_UNABLE : S_CLOSED;
                m.closed = now;
            }
        }

        /** A response from the aircraft arrived at the tower's copy of uplink `ref`. */
        public void responded(int ref, int downType, long now) {
            Msg m = byId(ref);
            if (m == null || !m.up || !m.open()) {
                return;
            }
            m.state = downType == D_WILCO ? S_WILCO : downType == D_UNABLE ? S_UNABLE : S_STBY;
            if (m.state == S_STBY) {
                m.sent = now;
            } else {
                m.closed = now;
            }
        }

        /** The clocks: unanswered uplinks / requests time out. @return how many timed out this call */
        public int tick(long now) {
            int n = 0;
            for (Msg m : msgs) {
                if (!m.open()) {
                    continue;
                }
                int limit = m.up ? m.state == S_STBY ? STBY_TICKS : RESP_TICKS : REQ_TICKS;
                if (now - m.sent > limit || now < m.sent - 24000) {
                    m.state = S_TIMEOUT;
                    m.closed = now;
                    n++;
                }
            }
            return n;
        }

        /** The newest `n` messages, newest first. */
        public List<Msg> recent(int n) {
            List<Msg> out = new ArrayList<Msg>();
            for (int i = msgs.size() - 1; i >= 0 && out.size() < n; i--) {
                out.add(msgs.get(i));
            }
            return out;
        }

        public void clear() {
            msgs.clear();
        }
    }
}
