package com.sva.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.airport.AptScale;
import com.sva.airport.AtisMath;
import com.sva.airport.AutoTower;
import com.sva.airport.Cpdlc;
import com.sva.airport.IlsPairing;
import com.sva.airport.RunwayLayout;
import com.sva.airport.RvrMath;
import com.sva.airport.TaxiGraph;
import com.sva.airport.TowerMath;
import com.sva.airport.WindField;
import com.sva.entity.EntityVehicleSVA;
import com.sva.net.NetADSVA;
import com.sva.net.NetAptSVA;
import com.sva.vehicle.AirfieldMath;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.VehicleType;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

/**
 * Airport wave B2 (plan-next §9, items 27, 29-32): the towers' air-traffic services on the server - run from
 * AirportServerSVA's tick for each loaded dimension, only for ONLINE towers (AirportDataSVA.Tower.online: the block reported
 * in lately, with power):
 * <ul>
 * <li><b>The runway in use</b> (every TOWER_TICKS): the tower screen's choice, else AUTO by the wind
 * (TowerMath.pickRunway - the head wind, the better ILS);</li>
 * <li><b>ATIS</b>: the observation (the runway, its approach, the weather station's wind / QNH / temperature, the
 * visibility, the RVR, the remarks) - a new letter when AtisMath.changed; pilots coming within TowerMath.ATC_RANGE get it in
 * the chat once per letter (and a tone);</li>
 * <li><b>The datalink</b>: one Session per aircraft in reach of a tower (its Cpdlc.Log, the automatic tower's view of it,
 * its taxi route), one log per tower (copies); uplinks and downlinks move between them (uplink / downlink / respond); a
 * new uplink chimes in the liner (the amber «ATC MSG» - LinerLogicSVA syncs the unread count), an aircraft without an MCDU
 * (the light plane, the jet...) gets it in the chat and on its HUD - it does not answer, so its uplinks need no response,
 * and its requests are guessed (on the final - landing; at a holding point - take-off);</li>
 * <li><b>The automatic tower</b> (AutoTower) for a tower without a player in its controller seat (airport.autoTower,
 * airport.controllerOverride);</li>
 * <li><b>Follow-me</b>: a taxi clearance gets a route through the taxi lights' graph (TaxiGraph) - to the holding point of
 * the runway in use, or after landing to the nearest stand; every TAXI_TICKS the lights ahead of each cleared aircraft are
 * lit, the stop bars burn unless dropped for a line-up / take-off clearance; the clients get the lit set (NetAptSVA D_TAXI)
 * when it changes;</li>
 * <li><b>The air-defence «аэродром» zone</b> of each tower (TowerMath.zone, AirDefenseDataSVA under «twr:» + its key).</li>
 * </ul>
 * Sessions and logs live only in memory (a restart starts them afresh); the ATIS letter and text are the tower's (saved).
 */
public final class AtcServerSVA {

    public static final AtcServerSVA INSTANCE = new AtcServerSVA();
    /** Periods (ticks): the towers (runway, ATIS, zone), the sessions, the taxi lights; a session unseen this long goes. */
    public static final int TOWER_TICKS = 20, SESSION_TICKS = 10, TAXI_TICKS = 5, FORGET = 400, ZONE_TICKS = 100;
    /** ATIS in the chat at most this often to one aircraft (ticks). */
    public static final int ATIS_GAP = 600;
    /** The tower's own radars (the owner's) within this far feed its traffic screen (blocks). */
    public static final double RADAR_LINK = 256;
    /** The air-defence zones' key prefix of the towers. */
    public static final String ZONE_KEY = "twr:";

    /** One aircraft in reach of a tower. */
    public static final class Session {
        public final int id;
        public final Cpdlc.Log log = new Cpdlc.Log();
        public final AutoTower.Ac ac = new AutoTower.Ac();
        /** The tower talking to it (key; "" none), it has an MCDU (a liner), last seen (ticks). */
        public String tower = "";
        public boolean mcdu;
        public long seen;
        /** The ATIS letter told per tower key, when last told. */
        public final Map<String, Integer> atisHeard = new HashMap<String, Integer>();
        public long atisAt = -100000;
        /** The follow-me route (node keys; null none), the progress along it. */
        public List<String> route;
        public int progress;
        /** The unread count it was chimed for; its squawk, mode, type (the strips). */
        public int unreadTold, code = -1, mode, type;
        public double course;
        /** Wave A: the code a tower gave it (-1 none) and which tower (its key). */
        public int sqCode = -1;
        public String sqTower = "";
        /**
         * Audit 2026-10-22: the vehicle's persistent id (the air defence checks the code was given to THIS aircraft); «ключ
         * дня» was on when the tower's code replaced it - switched back on once it leaves that airport (bug 9).
         */
        public String vid = "";
        public boolean sqKeyWas;

        Session(int id) {
            this.id = id;
            ac.id = id;
        }
    }

    private final Map<Integer, Session> sessions = new HashMap<Integer, Session>();
    private final Map<String, Cpdlc.Log> towerLogs = new HashMap<String, Cpdlc.Log>();
    private final Map<String, AutoTower.Mem> mems = new HashMap<String, AutoTower.Mem>();
    /** Stop bars dropped: the stop node's key -> {until (ticks), the aircraft cleared through}. */
    private final Map<String, long[]> stopDropped = new HashMap<String, long[]>();
    private final Map<Integer, String> taxiSent = new HashMap<Integer, String>();
    private final Map<Integer, Long> taxiSentAt = new HashMap<Integer, Long>();
    private final Map<String, ZoneMath.Zone> zonesSent = new HashMap<String, ZoneMath.Zone>();

    private AtcServerSVA() {
    }

    /** Everything forgotten (a server start / stop; the world tests). */
    public void clear() {
        sessions.clear();
        towerLogs.clear();
        mems.clear();
        stopDropped.clear();
        taxiSent.clear();
        taxiSentAt.clear();
        zonesSent.clear();
        IffServerSVA.INSTANCE.clear();
    }

    public Session session(int entityId) {
        return sessions.get(Integer.valueOf(entityId));
    }

    public Cpdlc.Log towerLog(String key) {
        Cpdlc.Log l = towerLogs.get(key);
        if (l == null) {
            l = new Cpdlc.Log();
            towerLogs.put(key, l);
        }
        return l;
    }

    /**
     * Audit 2026-10-22 (bug 6): did the tower `towerKey` give `code` to this very aircraft (its session: the entity id and
     * the persistent id)? The air defence counts an airport's code only then.
     */
    public boolean issued(String towerKey, int code, int entityId, String vid) {
        Session s = sessions.get(Integer.valueOf(entityId));
        return s != null && s.sqCode >= 0 && s.sqCode == code && s.sqTower.equals(towerKey) && (vid == null || vid.length() == 0 || s.vid.equals(vid));
    }

    /**
     * Code-only wave (proposal-ad-strict-iff item 11): did any tower give `code` to this very aircraft? A tower's 41xx is no
     * guess at a code-only post's key (CodeOnlyMath.probeCounts).
     */
    public boolean issuedAny(int code, int entityId, String vid) {
        Session s = sessions.get(Integer.valueOf(entityId));
        return s != null && s.sqCode >= 0 && s.sqCode == code && (vid == null || vid.length() == 0 || s.vid.equals(vid));
    }

    /** Every session (the IFF's view of the codes given). */
    public java.util.Collection<Session> allSessions() {
        return sessions.values();
    }

    /** The sessions talking to a tower. */
    public List<Session> sessionsOf(String towerKey) {
        List<Session> l = new ArrayList<Session>();
        for (Session s : sessions.values()) {
            if (s.tower.equals(towerKey)) {
                l.add(s);
            }
        }
        return l;
    }

    // ---------------------------------------------------------------- the tick

    /** One tick of world w's dimension (AirportServerSVA). */
    public void tick(World w, AirportDataSVA d, long now) {
        if (!ConfigSVA.aptAtc) {
            return;
        }
        if (now % TOWER_TICKS == 0) {
            towers(w, d, now);
        }
        if (now % SESSION_TICKS == 0) {
            sessions(w, d, now);
        }
        if (now % AutoTower.DECIDE_TICKS == 5) {
            auto(w, d, now);
        }
        if (now % TAXI_TICKS == 0) {
            taxi(w, d, now);
        }
        IffServerSVA.INSTANCE.tick(w, d, now);                      // wave A: the towers' IFF
    }

    /** The dimension's towers. */
    static List<AirportDataSVA.Tower> towersOf(AirportDataSVA d, int dim) {
        List<AirportDataSVA.Tower> l = new ArrayList<AirportDataSVA.Tower>();
        for (AirportDataSVA.Tower t : d.towers()) {
            if (t.dim == dim) {
                l.add(t);
            }
        }
        return l;
    }

    private void towers(World w, AirportDataSVA d, long now) {
        int dim = w.provider.dimensionId;
        for (AirportDataSVA.Tower t : towersOf(d, dim)) {
            runwayInUse(d, t);
            if (t.online(now)) {
                atis(w, d, t, now);
            }
            towerLog(t.key).tick(now);
            if ((now / TOWER_TICKS + (t.key.hashCode() & 7)) % (ZONE_TICKS / TOWER_TICKS) == 0) {
                zone(w, d, t);
            }
        }
    }

    // ---------------------------------------------------------------- the runway in use

    /** The station (FULL) of a runway end, null none. */
    static LinerIls.Station stationOf(AirportDataSVA d, IlsPairing.Rwy r, int dir) {
        for (IlsPairing.Pair p : d.pairs()) {
            if (p.r == r && p.dir == dir) {
                return d.station(p.key());
            }
        }
        return null;
    }

    /** The weather station of a tower's airport (null none). */
    static AirportDataSVA.Device weather(AirportDataSVA d, AirportDataSVA.Tower t) {
        double[] c = d.centre(t);
        return d.weatherNear(t.dim, c[0], c[1]);
    }

    /** The tower's runway in use now: its choice (an end of its runways), else by the wind. */
    static void runwayInUse(AirportDataSVA d, AirportDataSVA.Tower t) {
        List<TowerMath.End> ends = new ArrayList<TowerMath.End>();
        List<String> keys = new ArrayList<String>();
        for (IlsPairing.Rwy r : t.rwys) {
            for (int dir = 1; dir >= -1; dir -= 2) {
                if (r.end(dir).key.equals(t.rwySel)) {
                    t.inUse = t.rwySel;
                    return;
                }
                LinerIls.Station s = stationOf(d, r, dir);
                ends.add(new TowerMath.End(r.name(dir), r.course(dir), s == null || s.off() ? 0 : s.cat));
                keys.add(r.end(dir).key);
            }
        }
        AirportDataSVA.Device wx = weather(d, t);
        int i = TowerMath.pickRunway(ends, wx == null ? -1 : wx.windFrom, wx == null ? 0 : WindField.kmh(wx.windSpeed));
        t.inUse = i < 0 ? "" : keys.get(i);
    }

    /** The runway in use as AutoTower sees it (null none). */
    public static AutoTower.Rwy useRwy(AirportDataSVA.Tower t) {
        for (IlsPairing.Rwy r : t.rwys) {
            if (r.a.key.equals(t.inUse)) {
                return new AutoTower.Rwy(r, 1);
            }
            if (r.b.key.equals(t.inUse)) {
                return new AutoTower.Rwy(r, -1);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- ATIS

    /** What the ATIS reports now (AtisMath.Obs). */
    static AtisMath.Obs observe(World w, AirportDataSVA d, AirportDataSVA.Tower t) {
        AtisMath.Obs o = new AtisMath.Obs();
        AutoTower.Rwy rw = useRwy(t);
        if (rw != null) {
            o.rwy = rw.name();
            LinerIls.Station s = stationOf(d, rw.r, rw.dir);
            if (s != null && !s.off()) {
                o.appr = (s.flags & LinerIls.F_NO_GS) != 0 || (s.flags & LinerIls.F_GS_FAIL) != 0 ? AtisMath.A_LOC : AtisMath.A_ILS;
                o.cat = s.cat;
                if ((s.flags & LinerIls.F_UNCAL) != 0) {
                    o.rmk |= AtisMath.RMK_UNCAL;
                }
            } else if (s != null) {
                o.rmk |= AtisMath.RMK_ILS_OFF;
            }
            int[] rv = d.rvr(rw.r, rw.dir);
            o.rvr = rv;
        }
        AirportDataSVA.Device wx = weather(d, t);
        AirportDataSVA.Device here = new AirportDataSVA.Device();
        double[] c = d.centre(t);
        here.x = c[0];
        here.z = c[1];
        here.y = t.elev + 1;
        RvrMath.Wx x = AirportDataSVA.wx(w, here);
        if (wx == null) {
            o.windFrom = -1;
            o.rmk |= AtisMath.RMK_NO_WX;
        } else {
            o.windFrom = (int) Math.round(wx.windFrom) % 360;
            o.windKmh = WindField.kmh(wx.windSpeed);
            o.gustKmh = WindField.kmh(wx.windGust);
            o.temp = (int) Math.round(wx.temp);
            o.qnh = Double.isNaN(wx.qnh) ? 1013 : (int) Math.round(wx.qnh);
        }
        double vis = RvrMath.visibility(x);
        o.vis = vis >= 10000 ? 10000 : (int) (Math.round(vis / 100) * 100);
        if (x.thunder) {
            o.rmk |= AtisMath.RMK_THUNDER;
        }
        if (AtisMath.rvrCat(o.rvr) >= 2 || o.vis < RvrMath.CAT1_RVR) {
            o.rmk |= AtisMath.RMK_LVP;
        }
        if (!((List<?>) d.taxi(t.dim)[0]).isEmpty()) {
            o.rmk |= AtisMath.RMK_FOLLOW;
        }
        if (t.ltRwy == 0 && x.night) {
            o.rmk |= AtisMath.RMK_LIGHTS_OFF;
        }
        return o;
    }

    /** A new ATIS when the observation changed beyond the thresholds (the first one: АЛЬФА). */
    void atis(World w, AirportDataSVA d, AirportDataSVA.Tower t, long now) {
        // a weather station of the airport not measured yet (just placed / loaded): wait for its first reading
        double[] c = d.centre(t);
        for (AirportDataSVA.Device x : d.devices(t.dim, AirportDataSVA.K_WX)) {
            if (Double.isNaN(x.qnh) && (x.x - c[0]) * (x.x - c[0]) + (x.z - c[1]) * (x.z - c[1]) < AirportDataSVA.WX_RANGE * AirportDataSVA.WX_RANGE) {
                return;
            }
        }
        AtisMath.Obs o = observe(w, d, t);
        if (t.letter >= 0 && t.atisText.length() > 0 && !AtisMath.changed(t.atis, o)) {
            return;
        }
        t.letter = t.letter < 0 ? 0 : AtisMath.next(t.letter);
        t.atis = o;
        t.atisText = AtisMath.pack(AtisMath.text(t.icao, t.name, t.letter, w.getWorldTime(), o));
        t.atisAt = now;
        d.markDirty();
        System.out.println("[SVA-ATC] ATIS " + t.icao + " information " + (char) ('A' + t.letter) + " rwy " + o.rwy + " " + java.util.Arrays.toString(o.pack()));
    }

    /** An airport's ATIS for the MCDU: {letter, packed text}; null - no tower, never made, or the tower is off. */
    public String[] atisOf(AirportDataSVA d, String icao, long now) {
        AirportDataSVA.Tower t = d.towerByIcao(icao);
        if (t == null || t.letter < 0 || !t.online(now) || t.atisText.length() == 0) {
            return null;
        }
        return new String[] {String.valueOf(t.letter), t.atisText};
    }

    /** The ATIS as one chat line (translated on the reader's side). */
    static IChatComponent atisChat(AirportDataSVA.Tower t) {
        ChatComponentTranslation root = new ChatComponentTranslation("sva.atc.atisChat", t.icao);
        root.getChatStyle().setColor(EnumChatFormatting.AQUA);
        for (AtisMath.Line ln : AtisMath.unpack(t.atisText)) {
            root.appendSibling(new ChatComponentText(" "));
            root.appendSibling(translated(ln.key, ln.args));
            root.appendSibling(new ChatComponentText("."));
        }
        return root;
    }

    /**
     * A translation whose "#key" arguments are translations too. Audit review 2026-10-22: only the keys the ATIS / CPDLC put
     * there themselves (keyArg - none of them takes arguments); a tower's name, a runway, a free text starting with «#»
     * stay text: «#chat.type.text» became a translation without its arguments and the listener's chat threw on it.
     */
    static ChatComponentTranslation translated(String key, String[] args) {
        Object[] a = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            a[i] = keyArg(args[i]) ? new ChatComponentTranslation(args[i].substring(1)) : args[i];
        }
        return new ChatComponentTranslation(key, a);
    }

    /** The «#key» arguments the ATIS (the letter) and the CPDLC (a report) put in - translations without arguments of their own. */
    public static boolean keyArg(String a) {
        return a != null && (a.startsWith("#sva.atis.letter.") || a.startsWith("#sva.cpdlc.rep."));
    }

    /** World tests (audit review 2026-10-22): the ATIS / CPDLC chat line as translated() builds it. */
    public static ChatComponentTranslation debugTranslated(String key, String... args) {
        return translated(key, args);
    }

    // ---------------------------------------------------------------- the air-defence zone

    void zone(World w, AirportDataSVA d, AirportDataSVA.Tower t) {
        AirDefenseDataSVA ad = AirDefenseDataSVA.get(w);
        if (ad == null) {
            return;
        }
        if (!ConfigSVA.aptAdZone || t.icao.length() != 4) {
            if (zonesSent.remove(t.key) != null || !ConfigSVA.aptAdZone) {
                ad.removeKp(ZONE_KEY + t.key);
            }
            return;
        }
        ZoneMath.Zone z = TowerMath.zone(t.icao, t.name.length() > 0 ? t.name : t.icao, t.owner, t.dim, t.x, t.y, t.z, t.rwys, t.codeLo, t.codeHi);
        if (!TowerMath.sameZone(zonesSent.get(t.key), z)) {
            List<ZoneMath.Zone> l = new ArrayList<ZoneMath.Zone>();
            l.add(z);
            ad.setZones(ZONE_KEY + t.key, l);
            zonesSent.put(t.key, z);
        }
    }

    /** A tower gone: its zone, its log, its memory. */
    public void forgetTower(World w, String key) {
        AirDefenseDataSVA ad = AirDefenseDataSVA.get(w);
        if (ad != null) {
            ad.removeKp(ZONE_KEY + key);
        }
        zonesSent.remove(key);
        towerLogs.remove(key);
        mems.remove(key);
        for (Session s : sessions.values()) {
            if (s.tower.equals(key)) {
                s.tower = "";
            }
        }
    }

    // ---------------------------------------------------------------- the sessions

    /** The online tower nearest (x, z) within ATC_RANGE (null none). */
    static AirportDataSVA.Tower nearestTower(AirportDataSVA d, int dim, double x, double z, long now) {
        AirportDataSVA.Tower best = null;
        double bd = TowerMath.ATC_RANGE * TowerMath.ATC_RANGE;
        for (AirportDataSVA.Tower t : d.towers()) {
            if (t.dim != dim || !t.online(now)) {
                continue;
            }
            double[] c = d.centre(t);
            double dd = (c[0] - x) * (c[0] - x) + (c[1] - z) * (c[1] - z);
            if (dd <= bd) {
                bd = dd;
                best = t;
            }
        }
        return best;
    }

    /** The callsign of a vehicle (TowerMath.callsign). */
    public static String callsign(EntityVehicleSVA v) {
        String flt = v.state.liner() != null ? v.state.liner().fms.flt : "";
        XpdrMath.Xpdr x = v.state.xpdr;
        boolean rep = AirDefenseSVA.xpdrOn(v);                     // audit 2026-10-22: no power - no answer
        String c = TowerMath.callsign(flt, rep ? x.mode : XpdrMath.OFF, v.getBoardNumber(), rep ? x.code : -1);
        return "----".equals(c) ? "B-" + v.getBoardNumber() : c;
    }

    private void sessions(World w, AirportDataSVA d, long now) {
        int dim = w.provider.dimensionId;
        for (EntityVehicleSVA v : AirportDataSVA.aircraft(w)) {
            Integer id = Integer.valueOf(v.getEntityId());
            Session s = sessions.get(id);
            AirportDataSVA.Tower t = nearestTower(d, dim, v.posX, v.posZ, now);
            if (s == null) {
                if (t == null) {
                    continue;
                }
                s = new Session(v.getEntityId());
                sessions.put(id, s);
            }
            s.seen = now;
            s.vid = AirDefenseSVA.vehKey(v);
            s.mcdu = v.getVehicleType() == VehicleType.LINER;
            s.type = v.getVehicleType().ordinal();
            AutoTower.Ac a = s.ac;
            a.x = v.posX;
            a.y = v.posY;
            a.z = v.posZ;
            double[] vel = AirDefenseSVA.INSTANCE.velocity(v);
            a.gs = Math.sqrt(vel[0] * vel[0] + vel[2] * vel[2]);
            s.course = Math.sqrt(vel[0] * vel[0] + vel[2] * vel[2]) < 1e-3 ? com.sva.airdefense.RadarMath.headingOfYaw(v.rotationYaw)
                    : com.sva.airdefense.RadarMath.compass(vel[0], vel[2]);
            a.ground = !v.airborneForAtc();
            a.call = callsign(v);
            XpdrMath.Xpdr x = v.state.xpdr;
            s.mode = XpdrMath.fitted(v.getVehicleType()) ? x.mode : XpdrMath.OFF;
            s.code = AirDefenseSVA.xpdrOn(v) ? x.code : -1;
            restoreDayKey(d, s, v);
            if (t == null) {
                s.tower = "";
                continue;
            }
            if (!t.key.equals(s.tower)) {
                s.tower = t.key;
                a.req = AutoTower.R_NONE;
                a.st = AutoTower.S_NONE;
                a.stbyTold = false;
                s.route = null;
            }
            if (!s.mcdu) {
                guessRequest(d, t, s, now);
            }
            // wave A: an arrival entering the airport's zone with mode C / S on gets the airport's code (once per tower) - not one
            // already in the landing sequence (it asked to land / is cleared / goes around: no new code on the final). Audit
            // 2026-10-22: offerSquawk gives it only to the tower's own / trusted (bug 5), never over 7500 / 7600 / 7700 or a
            // running day key (bug 9)
            if (!a.ground && !s.sqTower.equals(t.key) && AirDefenseSVA.xpdrOn(v) && XpdrMath.givesAltitude(x.mode)
                    && a.req != AutoTower.R_LAND && a.st != AutoTower.S_LAND && a.st != AutoTower.S_GOAROUND
                    && ZoneMath.contains2D(IffServerSVA.zoneOf(t), v.posX, v.posZ)) {
                offerSquawk(w, d, t, s, v, now);
            }
            // ATIS in the chat: once per letter, not more often than ATIS_GAP
            Integer heard = s.atisHeard.get(t.key);
            if (ConfigSVA.aptAtisChat && t.letter >= 0 && t.atisText.length() > 0 && (heard == null || heard.intValue() != t.letter)
                    && now - s.atisAt >= ATIS_GAP) {
                s.atisHeard.put(t.key, Integer.valueOf(t.letter));
                s.atisAt = now;
                tellAtis(v, t);
            }
            // a new uplink: the chime (the liner's lamps follow the unread count)
            int unread = s.log.unread();
            if (unread > s.unreadTold && s.mcdu) {
                w.playSoundEffect(v.posX, v.posY, v.posZ, "note.pling", 0.9F, 1.7F);
                w.playSoundEffect(v.posX, v.posY, v.posZ, "note.pling", 0.7F, 1.35F);
            }
            s.unreadTold = unread;
            s.log.tick(now);
        }
        for (Iterator<Map.Entry<Integer, Session>> it = sessions.entrySet().iterator(); it.hasNext();) {
            Session s = it.next().getValue();
            Entity e = w.getEntityByID(s.id);
            if (now - s.seen > FORGET || now < s.seen - 24000 || e != null && e.isDead) {
                if (e == null && now - s.seen <= FORGET * 10 && now >= s.seen) {
                    continue;                                       // another dimension's, or unloaded a moment
                }
                it.remove();
            }
        }
    }

    /** An aircraft without an MCDU can't ask: on the final it wants to land, stopped at a holding point it is ready. */
    void guessRequest(AirportDataSVA d, AirportDataSVA.Tower t, Session s, long now) {
        AutoTower.Ac a = s.ac;
        AutoTower.Rwy rw = useRwy(t);
        if (rw == null || a.req != AutoTower.R_NONE || a.st != AutoTower.S_NONE && a.st != AutoTower.S_TAXI) {
            return;
        }
        if (!a.ground && AutoTower.onFinal(rw, a) && AutoTower.final_(rw, a)[0] < AutoTower.LAND_WINDOW && a.y - rw.r.elev < 150) {
            a.req = AutoTower.R_LAND;
            a.reqAt = now;
            a.stbyTold = false;
        } else if (a.ground && a.gs < 0.1 && s.route == null) {
            @SuppressWarnings("unchecked")
            List<TaxiGraph.Node> nodes = (List<TaxiGraph.Node>) d.taxi(t.dim)[0];
            int hp = TaxiGraph.nearest(nodes, a.x, a.z, 4, TaxiGraph.STOP);
            double[] th = rw.r.threshold(rw.dir);
            boolean atThreshold = AutoTower.onRunway(rw, a) && Math.hypot(a.x - th[0], a.z - th[1]) < 30;
            if (hp >= 0 || atThreshold) {
                a.req = AutoTower.R_TAKEOFF;
                a.reqAt = now;
                a.stbyTold = false;
            }
        }
    }

    void tellAtis(EntityVehicleSVA v, AirportDataSVA.Tower t) {
        IChatComponent c = null;
        for (Entity e : v.riders()) {
            if (e instanceof EntityPlayer) {
                if (c == null) {
                    c = atisChat(t);
                }
                ((EntityPlayer) e).addChatComponentMessage(c);
            }
        }
        if (c != null) {
            v.worldObj.playSoundEffect(v.posX, v.posY, v.posZ, "note.harp", 0.6F, 1.2F);
        }
    }

    // ---------------------------------------------------------------- uplinks / downlinks

    /** Is the tower run by the automatic rules now (no controller who takes over)? */
    public static boolean automatic(AirportDataSVA.Tower t) {
        return ConfigSVA.aptAutoTower && (t.controller.length() == 0 || !ConfigSVA.aptControllerOverride);
    }

    /** The newest open request of a kind answering an uplink type (-1 none). */
    static int refFor(Session s, int upType) {
        int[] kinds = upType == Cpdlc.U_TAXI ? new int[] {Cpdlc.D_TAXI}
                : upType == Cpdlc.U_LINEUP || upType == Cpdlc.U_TAKEOFF || upType == Cpdlc.U_HOLDSHORT ? new int[] {Cpdlc.D_READY}
                : upType == Cpdlc.U_LAND ? new int[] {Cpdlc.D_LANDING}
                : upType == Cpdlc.U_CLIMB || upType == Cpdlc.U_DESCEND ? new int[] {Cpdlc.D_CLIMB} : new int[0];
        for (int k : kinds) {
            Cpdlc.Msg m = s.log.openRequest(k);
            if (m != null) {
                return m.id;
            }
        }
        return -1;
    }

    /**
     * An uplink from a tower to an aircraft (the automatic tower or the controller): into the aircraft's log (unread) and the
     * tower's; a taxi clearance gets its follow-me route, a line-up / take-off one drops the stop bar; the clearance moves the
     * automatic tower's view of the aircraft. `ref` -2: the newest open request it answers (refFor). @return the message
     */
    public Cpdlc.Msg uplink(World w, AirportDataSVA d, AirportDataSVA.Tower t, Session s, int type, int arg, String str, int ref, long now) {
        return uplink(w, d, t, s, type, arg, str, ref, now, false);
    }

    /**
     * As above; `controller`: sent by the player in the controller seat (audit 2026-10-22, bug 5: his line-up / take-off
     * clearance gives the airport's code to any aircraft - the automatic tower's only to the tower's own / trusted).
     */
    public Cpdlc.Msg uplink(World w, AirportDataSVA d, AirportDataSVA.Tower t, Session s, int type, int arg, String str, int ref, long now,
            boolean controller) {
        if (type < 0 || type >= Cpdlc.U_TYPES) {
            return null;
        }
        if (ref == -2) {
            ref = refFor(s, type);
        }
        String s2 = "";
        if (type == Cpdlc.U_TAXI) {
            s2 = taxiRoute(d, t, s, now);
        }
        if (type == Cpdlc.U_FREE) {
            str = Cpdlc.cleanFree(str);
        }
        Cpdlc.Msg m = s.log.make(true, type, arg, str, s2, ref, now);
        m.tower = t.icao;
        m.call = s.ac.call;
        m.aircraft = s.id;
        if (!s.mcdu) {
            m.state = Cpdlc.S_CLOSED;                               // no MCDU - no response expected
            m.read = true;
        }
        s.log.add(m);
        if (ref >= 0) {
            int as = type == Cpdlc.U_HOLDSHORT ? Cpdlc.U_STBY : type;
            s.log.answered(ref, as, now);
            Cpdlc.Msg tr = towerMsg(t.key, s.id, ref);
            if (tr != null) {
                tr.state = s.log.byId(ref) == null ? tr.state : s.log.byId(ref).state;
                tr.closed = now;
            }
        }
        towerLog(t.key).add(m.copy());
        // the automatic tower's view follows any clearance
        AutoTower.Ac a = s.ac;
        switch (type) {
            case Cpdlc.U_TAXI:
                a.st = AutoTower.S_TAXI;
                a.stAt = now;
                if (a.req == AutoTower.R_TAXI) {
                    a.req = AutoTower.R_NONE;
                }
                break;
            case Cpdlc.U_LINEUP:
            case Cpdlc.U_TAKEOFF:
                a.st = type == Cpdlc.U_TAKEOFF ? AutoTower.S_TAKEOFF : AutoTower.S_LINEUP;
                a.stAt = now;
                dropStopBar(d, t, s, now);
                break;
            case Cpdlc.U_LAND:
                a.st = AutoTower.S_LAND;
                a.stAt = now;
                break;
            case Cpdlc.U_GOAROUND:
                a.st = AutoTower.S_GOAROUND;
                a.stAt = now;
                a.req = AutoTower.R_NONE;
                break;
            case Cpdlc.U_HOLDSHORT:
                a.st = AutoTower.S_HOLD;
                break;
            default:
                break;
        }
        deliver(w, s, m);
        if (type == Cpdlc.U_TAKEOFF || type == Cpdlc.U_LINEUP) {
            // wave A: a departure gets the airport's code with its take-off / line-up clearance
            Entity e = w.getEntityByID(s.id);
            if (e instanceof EntityVehicleSVA && !s.sqTower.equals(t.key)) {
                offerSquawk(w, d, t, s, (EntityVehicleSVA) e, now, controller);
            }
        }
        return m;
    }

    // ---------------------------------------------------------------- wave A: the airport's transponder codes

    /**
     * The airport's code for an aircraft (AirportCodeMath.assign: its own code when it already squawks one of the range nobody
     * else holds, else the first free one): the liner gets the CPDLC uplink «КОД ОТВЕТЧИКА 41xx» (its WILCO sets it -
     * respond()), the others have it set at once (airdefense.autoSquawk) and told in the chat. @return the code (-1 none)
     */
    public int offerSquawk(World w, AirportDataSVA d, AirportDataSVA.Tower t, Session s, EntityVehicleSVA v, long now) {
        return offerSquawk(w, d, t, s, v, now, false);
    }

    /**
     * Audit 2026-10-22 (bugs 5, 9): `explicit` - the controller gives / clears it by hand. Otherwise only to the aircraft of
     * the tower's owner, his access list or the tower's «коды: доверенные» list (AirportCodeMath.mayAssign) - a stranger
     * entering the zone no longer becomes the air defence's «свой» on its own. Never over 7500 / 7600 / 7700 or a running day
     * key (AirportCodeMath.mayReplace; not «asked» either - once that is over it may get one). @return the code (-1 none)
     */
    public int offerSquawk(World w, AirportDataSVA d, AirportDataSVA.Tower t, Session s, EntityVehicleSVA v, long now, boolean explicit) {
        if (!XpdrMath.fitted(v.getVehicleType())) {
            return -1;
        }
        XpdrMath.Xpdr x0 = v.state.xpdr;
        // code-only wave (item 19): the КЛЮЧ ДНЯ lamp on the key's clock (AirDefenseDataSVA.keyPeriod), as everywhere
        if (!com.sva.airdefense.AirportCodeMath.mayReplace(x0.code, x0.keyLamp(com.sva.airdefense.AirDefenseDataSVA.keyPeriod(w)))) {
            return -1;
        }
        if (!explicit && !com.sva.airdefense.AirportCodeMath.mayAssign(t.owner, t.access, t.sqTrust, v.getOwnerName())) {
            return -1;
        }
        java.util.Set<Integer> used = new java.util.HashSet<Integer>();
        for (Session o : sessions.values()) {
            if (o.id != s.id) {
                if (o.sqTower.equals(t.key) && o.sqCode >= 0) {
                    used.add(Integer.valueOf(o.sqCode));
                }
                if (o.tower.equals(t.key) && o.code >= 0) {
                    used.add(Integer.valueOf(o.code));
                }
            }
        }
        XpdrMath.Xpdr x = v.state.xpdr;
        int keep = s.sqTower.equals(t.key) ? s.sqCode : x.code;
        int code = com.sva.airdefense.AirportCodeMath.assign(t.codeLo, t.codeHi, used, keep);
        s.sqTower = t.key;                                          // asked once per tower, even when the range is full
        if (code < 0) {
            return -1;
        }
        s.sqCode = code;
        System.out.println("[SVA-ATC] squawk " + t.icao + " -> " + s.ac.call + ": " + XpdrMath.format(code));
        if (x.code == code && !x.dayKey) {
            return code;                                            // squawks it already
        }
        if (!s.mcdu && ConfigSVA.adAutoSquawk) {
            towerSquawk(s, v, code, now);
        }
        if (s.tower.equals(t.key)) {
            uplink(w, d, t, s, Cpdlc.U_SQUAWK, code, "", -1, now);
        } else {
            AirDefenseSVA.INSTANCE.tellPilot(v, "sq" + t.key, 0, 0x5EC8F0, "sva.atc.squawk", t.icao, XpdrMath.format(code));
        }
        return code;
    }

    /** A tower's code into the transponder, the day key remembered (bug 9: back on once the aircraft leaves the airport). */
    static void towerSquawk(Session s, EntityVehicleSVA v, int code, long now) {
        s.sqKeyWas |= v.state.xpdr.dayKey;
        setSquawk(v, code, now);
    }

    /**
     * Audit 2026-10-22 (bug 9): the tower's code replaced a «ключ дня» - once the aircraft is away from that airport (out of
     * its zone and NEAR) and still squawks the code it was given, the day key goes back on (the card sets today's code).
     */
    void restoreDayKey(AirportDataSVA d, Session s, EntityVehicleSVA v) {
        if (!s.sqKeyWas || s.sqTower.length() == 0) {
            return;
        }
        AirportDataSVA.Tower st = d.tower(s.sqTower);
        if (st != null && com.sva.airdefense.AirportCodeMath.nearAirport(IffServerSVA.zoneOf(st), v.posX, v.posY, v.posZ, true)) {
            return;
        }
        XpdrMath.Xpdr x = v.state.xpdr;
        if (x.code == s.sqCode && !x.dayKey) {
            x.dayKey = true;
            AirDefenseSVA.INSTANCE.tellPilot(v, "sqKey" + s.sqTower, 0, 0x5EC8F0, "sva.atc.squawkKey");
        }
        s.sqKeyWas = false;
    }

    /** The code into the transponder (the «ключ дня» card no longer overrides it). */
    public static void setSquawk(EntityVehicleSVA v, int code, long now) {
        XpdrMath.Xpdr x = v.state.xpdr;
        x.setCode(code, now);
        x.heldCode = -1;                                            // review 2026-10-27: newer than a panel's code still waiting
        x.dayKey = false;
        x.dropCard(false, now);                                     // code-only wave: the card it came from forgotten too
    }

    /** The tower's copy of an aircraft's message (null none). */
    Cpdlc.Msg towerMsg(String towerKey, int aircraft, int id) {
        for (Cpdlc.Msg m : towerLog(towerKey).msgs) {
            if (m.aircraft == aircraft && m.id == id) {
                return m;
            }
        }
        return null;
    }

    /** The uplink reaches the aircraft: the chime / the lamp (a liner), the chat and the HUD line (the others). */
    void deliver(World w, Session s, Cpdlc.Msg m) {
        Entity e = w.getEntityByID(s.id);
        if (!(e instanceof EntityVehicleSVA)) {
            return;
        }
        EntityVehicleSVA v = (EntityVehicleSVA) e;
        System.out.println("[SVA-ATC] uplink " + m.tower + " -> " + m.call + ": " + Cpdlc.key(m) + " " + java.util.Arrays.toString(Cpdlc.args(m)));
        if (s.mcdu) {
            return;                                                 // the chime comes with the unread count (sessions)
        }
        for (Entity r : v.riders()) {
            if (r instanceof EntityPlayerMP) {
                EntityPlayerMP p = (EntityPlayerMP) r;
                ChatComponentTranslation c = new ChatComponentTranslation("sva.atc.chat", m.tower, translated(Cpdlc.key(m), Cpdlc.args(m)));
                c.getChatStyle().setColor(EnumChatFormatting.AQUA);
                p.addChatComponentMessage(c);
                NBTTagCompound t = new NBTTagCompound();
                t.setString("K", Cpdlc.key(m));
                NBTTagList a = new NBTTagList();
                for (String x : Cpdlc.args(m)) {
                    a.appendTag(new net.minecraft.nbt.NBTTagString(keyArg(x) ? x : com.sva.airdefense.AirDefenseSVA.hudArg(x)));   // audit review
                }
                t.setTag("A", a);
                t.setInteger("C", m.type == Cpdlc.U_GOAROUND ? 0xF05050 : 0x5EC8F0);
                NetADSVA.send(p, NetADSVA.D_MSG, t);
            }
        }
    }

    /** A downlink from an aircraft (its MCDU). @return sent (false: no tower in reach) */
    public boolean downlink(World w, AirportDataSVA d, EntityVehicleSVA v, int type, int arg, String str, long now) {
        Session s = session(v.getEntityId());
        AirportDataSVA.Tower t = s == null || s.tower.length() == 0 ? null : d.tower(s.tower);
        if (t == null || !t.online(now) || type < Cpdlc.D_FIRST || type > Cpdlc.D_FREE) {
            return false;
        }
        IffServerSVA.INSTANCE.answer(w, v.getEntityId(), t.key);    // wave A: any word from the crew answers the IFF call
        Cpdlc.Msg m = s.log.make(false, type, arg, type == Cpdlc.D_FREE ? Cpdlc.cleanFree(str) : str, "", -1, now);
        m.tower = t.icao;
        m.call = s.ac.call;
        m.aircraft = s.id;
        s.log.add(m);
        towerLog(t.key).add(m.copy());
        AutoTower.Ac a = s.ac;
        int req = type == Cpdlc.D_TAXI ? AutoTower.R_TAXI : type == Cpdlc.D_READY ? AutoTower.R_TAKEOFF : type == Cpdlc.D_LANDING ? AutoTower.R_LAND
                : AutoTower.R_NONE;
        if (req != AutoTower.R_NONE) {
            a.req = req;
            a.reqAt = now;
            a.stbyTold = false;
        }
        if (type == Cpdlc.D_CLIMB && automatic(t)) {
            // the automatic tower answers a climb at once: cleared (within the world's top), else unable
            boolean ok = arg >= 1 && arg <= 255;
            uplink(w, d, t, s, ok ? Cpdlc.U_CLIMB : Cpdlc.U_UNABLE, arg, "", m.id, now);
        }
        System.out.println("[SVA-ATC] downlink " + m.call + " -> " + t.icao + ": " + Cpdlc.key(m) + " " + java.util.Arrays.toString(Cpdlc.args(m)));
        return true;
    }

    /** The crew's response to an uplink. @return sent */
    public boolean respond(EntityVehicleSVA v, int id, int r, long now) {
        Session s = session(v.getEntityId());
        if (s == null) {
            return false;
        }
        Cpdlc.Msg resp = s.log.respond(id, r, now);
        if (resp == null) {
            return false;
        }
        Cpdlc.Msg up = s.log.byId(id);
        if (up != null && up.up && up.type == Cpdlc.U_SQUAWK && r == Cpdlc.R_WILCO) {
            towerSquawk(s, v, up.arg & XpdrMath.MAX, now);          // wave A: the crew dials the airport's code
        }
        if (up != null && up.up && up.type == Cpdlc.U_IFF && s.tower.length() > 0) {
            IffServerSVA.INSTANCE.answer(v.worldObj, v.getEntityId(), s.tower);
        }
        if (s.tower.length() > 0) {
            Cpdlc.Log tl = towerLog(s.tower);
            tl.add(resp.copy());
            Cpdlc.Msg tm = towerMsg(s.tower, s.id, id);
            Cpdlc.Msg am = s.log.byId(id);
            if (tm != null && am != null) {
                tm.state = am.state;
                tm.closed = am.closed;
                tm.sent = am.sent;
            }
        }
        return true;
    }

    public void read(EntityVehicleSVA v, int id) {
        Session s = session(v.getEntityId());
        if (s == null) {
            return;
        }
        if (id < 0) {
            s.log.readAll();
        } else if (s.log.byId(id) != null) {
            s.log.byId(id).read = true;
        }
    }

    public void loaded(EntityVehicleSVA v, int id) {
        Session s = session(v.getEntityId());
        if (s != null && s.log.byId(id) != null) {
            s.log.byId(id).loaded = true;
        }
    }

    /** The unread uplinks of a vehicle (the liner's «ATC MSG»). */
    public int unread(int entityId) {
        Session s = session(entityId);
        return s == null ? 0 : s.log.unread();
    }

    // ---------------------------------------------------------------- the automatic tower

    private void auto(World w, AirportDataSVA d, long now) {
        int dim = w.provider.dimensionId;
        for (AirportDataSVA.Tower t : towersOf(d, dim)) {
            if (!t.online(now) || !automatic(t)) {
                continue;
            }
            AutoTower.Rwy rw = useRwy(t);
            if (rw == null) {
                continue;
            }
            List<Session> ss = sessionsOf(t.key);
            List<AutoTower.Ac> traffic = new ArrayList<AutoTower.Ac>();
            Map<Integer, Session> byId = new HashMap<Integer, Session>();
            for (Session s : ss) {
                if (now - s.seen <= SESSION_TICKS * 3) {
                    traffic.add(s.ac);
                    byId.put(Integer.valueOf(s.id), s);
                }
            }
            AutoTower.Mem mem = mems.get(t.key);
            if (mem == null) {
                mem = new AutoTower.Mem();
                mems.put(t.key, mem);
            }
            AutoTower.Params p = new AutoTower.Params();
            p.interval = ConfigSVA.aptAutoInterval * 20;
            p.gaHeight = ConfigSVA.aptGoAroundHeight;
            for (AutoTower.Act act : AutoTower.decide(traffic, rw, mem, now, p)) {
                Session s = byId.get(Integer.valueOf(act.id));
                if (s == null) {
                    continue;
                }
                int ref = act.type == Cpdlc.U_STBY ? stbyRef(s) : -2;
                // decide() moved the state already; uplink() sets it the same way
                uplink(w, d, t, s, act.type, 0, act.rwy, ref, now);
            }
        }
    }

    /** The request a standby answers (the newest open one of any kind). */
    static int stbyRef(Session s) {
        for (int k : new int[] {Cpdlc.D_LANDING, Cpdlc.D_READY, Cpdlc.D_TAXI, Cpdlc.D_CLIMB}) {
            Cpdlc.Msg m = s.log.openRequest(k);
            if (m != null) {
                return m.id;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- follow-me

    /**
     * A taxi clearance's route (the session's): from the light nearest the aircraft to the holding point of the runway in use
     * (a departure), or - near the runway, after landing - to the nearest stand. @return its length in m-eq ("" no route)
     */
    @SuppressWarnings("unchecked")
    String taxiRoute(AirportDataSVA d, AirportDataSVA.Tower t, Session s, long now) {
        Object[] g = d.taxi(t.dim);
        List<TaxiGraph.Node> nodes = (List<TaxiGraph.Node>) g[0];
        List<List<Integer>> adj = (List<List<Integer>>) g[1];
        s.route = null;
        s.progress = 0;
        if (nodes.isEmpty()) {
            return "";
        }
        AutoTower.Ac a = s.ac;
        int from = TaxiGraph.nearest(nodes, a.x, a.z, 40, -1);
        AutoTower.Rwy rw = useRwy(t);
        boolean arrival = rw != null && rw.r.onRunway(a.x, a.z, 14);
        int to;
        if (arrival || rw == null) {
            to = TaxiGraph.nearest(nodes, a.x, a.z, Double.MAX_VALUE / 4, TaxiGraph.STAND);
        } else {
            double[] th = rw.r.threshold(rw.dir);
            to = TaxiGraph.holdingPoint(nodes, th[0], th[1]);
        }
        List<Integer> r = TaxiGraph.route(nodes, adj, from, to);
        if (r.size() < 2) {
            return "";
        }
        s.route = new ArrayList<String>();
        for (Integer i : r) {
            s.route.add(nodes.get(i.intValue()).key);
        }
        return String.valueOf((int) Math.round(AptScale.metres(TaxiGraph.length(nodes, r))));
    }

    /** A line-up / take-off clearance drops the stop bar the aircraft holds at (its route's end, else the nearest within 15). */
    @SuppressWarnings("unchecked")
    void dropStopBar(AirportDataSVA d, AirportDataSVA.Tower t, Session s, long now) {
        List<TaxiGraph.Node> nodes = (List<TaxiGraph.Node>) d.taxi(t.dim)[0];
        String key = null;
        if (s.route != null && !s.route.isEmpty()) {
            int i = TaxiGraph.index(nodes, s.route.get(s.route.size() - 1));
            if (i >= 0 && nodes.get(i).kind == TaxiGraph.STOP) {
                key = nodes.get(i).key;
            }
        }
        if (key == null) {
            int i = TaxiGraph.nearest(nodes, s.ac.x, s.ac.z, 15, TaxiGraph.STOP);
            key = i < 0 ? null : nodes.get(i).key;
        }
        if (key != null) {
            stopDropped.put(key, new long[] {now + TaxiGraph.STOP_RELIGHT, s.id});
        }
        s.route = null;
    }

    /** The tower a taxi light belongs to: the nearest within TowerMath.RANGE (null none). */
    static AirportDataSVA.Tower towerOfLight(List<AirportDataSVA.Tower> ts, double x, double z) {
        AirportDataSVA.Tower best = null;
        double bd = TowerMath.RANGE * TowerMath.RANGE;
        for (AirportDataSVA.Tower t : ts) {
            double dd = (t.x - x) * (t.x - x) + (t.z - z) * (t.z - z);
            if (dd <= bd) {
                bd = dd;
                best = t;
            }
        }
        return best;
    }

    /** The lit set of a dimension: {follow-me on, lit centre lights (indices), lit stop bars (indices)}. */
    @SuppressWarnings("unchecked")
    Object[] litSet(World w, AirportDataSVA d, long now) {
        int dim = w.provider.dimensionId;
        Object[] g = d.taxi(dim);
        List<TaxiGraph.Node> nodes = (List<TaxiGraph.Node>) g[0];
        List<AirportDataSVA.Device> all = (List<AirportDataSVA.Device>) g[2];
        Map<String, Integer> index = new HashMap<String, Integer>();
        for (int i = 0; i < all.size(); i++) {
            index.put(all.get(i).key, Integer.valueOf(i));
        }
        TreeSet<Integer> lit = new TreeSet<Integer>(), stops = new TreeSet<Integer>();
        boolean follow = false;
        List<AirportDataSVA.Tower> ts = towersOf(d, dim);
        for (Session s : sessions.values()) {
            AirportDataSVA.Tower t = s.tower.length() == 0 ? null : d.tower(s.tower);
            if (s.route == null || t == null || t.dim != dim || !t.online(now)) {
                continue;
            }
            List<Integer> r = new ArrayList<Integer>();
            for (String k : s.route) {
                int i = TaxiGraph.index(nodes, k);
                if (i >= 0) {
                    r.add(Integer.valueOf(i));
                }
            }
            s.progress = TaxiGraph.progress(nodes, r, s.progress, s.ac.x, s.ac.z);
            if (TaxiGraph.arrived(r, s.progress) && s.ac.gs < 0.05 || !s.ac.ground) {
                s.route = null;                                     // there (stopped at its end) or airborne: the route is done
                continue;
            }
            follow = true;
            for (Integer i : TaxiGraph.lit(r, s.progress, ConfigSVA.aptTaxiAhead)) {
                Integer k = index.get(nodes.get(i.intValue()).key);
                if (k != null) {
                    lit.add(k);
                }
            }
        }
        for (Iterator<Map.Entry<String, long[]>> it = stopDropped.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, long[]> e = it.next();
            Session s = session((int) e.getValue()[1]);
            int i = TaxiGraph.index(nodes, e.getKey());
            boolean passed = s == null || i >= 0 && Math.hypot(s.ac.x - nodes.get(i).x, s.ac.z - nodes.get(i).z) > 25;
            if (now >= e.getValue()[0] || passed && now - (e.getValue()[0] - TaxiGraph.STOP_RELIGHT) > 40) {
                it.remove();
            }
        }
        for (int i = 0; i < all.size(); i++) {
            AirportDataSVA.Device dv = all.get(i);
            if (dv.kind != AirportDataSVA.K_STOP) {
                continue;
            }
            AirportDataSVA.Tower t = towerOfLight(ts, dv.x, dv.z);
            long[] dr = stopDropped.get(dv.key);
            if (TaxiGraph.stopLit(t != null && t.online(now), dr == null ? 0 : dr[0], now)) {
                stops.add(Integer.valueOf(i));
            }
        }
        return new Object[] {Boolean.valueOf(follow), lit, stops, Integer.valueOf(d.taxiVersion())};
    }

    /** Is a stop bar (its key) burning now - the world test. */
    @SuppressWarnings("unchecked")
    public boolean stopLitNow(World w, AirportDataSVA d, String key, long now) {
        Object[] l = litSet(w, d, now);
        List<AirportDataSVA.Device> all = (List<AirportDataSVA.Device>) d.taxi(w.provider.dimensionId)[2];
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).key.equals(key)) {
                return ((TreeSet<Integer>) l[2]).contains(Integer.valueOf(i));
            }
        }
        return false;
    }

    /** The lit centre lights' keys now - the world test. */
    @SuppressWarnings("unchecked")
    public List<String> litNow(World w, AirportDataSVA d, long now) {
        Object[] l = litSet(w, d, now);
        List<AirportDataSVA.Device> all = (List<AirportDataSVA.Device>) d.taxi(w.provider.dimensionId)[2];
        List<String> out = new ArrayList<String>();
        for (Integer i : (TreeSet<Integer>) l[1]) {
            out.add(all.get(i.intValue()).key);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void taxi(World w, AirportDataSVA d, long now) {
        int dim = w.provider.dimensionId;
        if (((List<?>) d.taxi(dim)[2]).isEmpty()) {
            return;
        }
        Object[] l = litSet(w, d, now);
        StringBuilder sig = new StringBuilder();
        sig.append(l[3]).append('|').append(l[0]).append('|').append(l[1]).append('|').append(l[2]);
        String was = taxiSent.get(Integer.valueOf(dim));
        Long at = taxiSentAt.get(Integer.valueOf(dim));
        if (sig.toString().equals(was) && at != null && now - at.longValue() < 200 && now >= at.longValue()) {
            return;
        }
        taxiSent.put(Integer.valueOf(dim), sig.toString());
        taxiSentAt.put(Integer.valueOf(dim), Long.valueOf(now));
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger("V", ((Integer) l[3]).intValue());
        t.setBoolean("F", ((Boolean) l[0]).booleanValue());
        t.setIntArray("L", ints((TreeSet<Integer>) l[1]));
        t.setIntArray("S", ints((TreeSet<Integer>) l[2]));
        for (Object o : w.playerEntities) {
            if (o instanceof EntityPlayerMP) {
                NetAptSVA.send((EntityPlayerMP) o, NetAptSVA.D_TAXI, t);
            }
        }
    }

    static int[] ints(TreeSet<Integer> s) {
        int[] a = new int[s.size()];
        int k = 0;
        for (Integer i : s) {
            a[k++] = i.intValue();
        }
        return a;
    }

    // ---------------------------------------------------------------- the lights' step of the taxiways (AUTO by the conditions)

    /** The taxiway lights' step of a tower now (its mode; AUTO: the runway's rule by the night / rain). */
    public static int taxiStep(AirportDataSVA.Tower t, boolean night, boolean rain) {
        return RunwayLayout.step(t.ltTaxi, night, rain, 10000);
    }

    /** Night at a world (the airfield's rule). */
    public static boolean night(World w) {
        return AirfieldMath.night(w.getCelestialAngle(1F));
    }
}
