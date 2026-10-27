package com.sva.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.sva.airport.EmbassyAirportMath;
import com.sva.airport.EmbassyAirportSVA;
import com.sva.airport.EmbassyAtcMath;
import com.sva.airport.IlsPairing;
import com.sva.airport.RunwayLayout;
import com.sva.airport.RvrMath;
import com.sva.airport.TaxiGraph;
import com.sva.airport.TowerMath;
import com.sva.airport.WindField;
import com.sva.compat.EmbassyCompatSVA;
import com.sva.compat.EmbassyFieldSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.entity.LinerLogicSVA;
import com.sva.net.NetADSVA;
import com.sva.net.NetAptSVA;
import com.sva.vehicle.AirfieldMath;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.VehicleType;

import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.FakePlayer;

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
 * <li><b>Embassy airfields</b> (docs/plan-embassy-airfield-addon.md §3.2; the virtual towers of airport.EmbassyAirportSVA): the ATIS in
 * the chat only near the field or for the pilots bound for it (EmbassyAtcMath.atisToChat), the Tuners' own weather when no station is
 * near, the remarks (AtisMath.embassyRemarks); the embassy's «Враг» at the controls - ОБСЛУЖИВАНИЕ ЗАПРЕЩЕНО to his landing / taxi /
 * take-off requests, a landing request over the dome - ПОСАДКА В КУПОЛЕ ЗАПРЕЩЕНА, the liner on a SHORT runway - refused (7700: nothing
 * refused); the codes 5100..5177 to everybody but the «Враг»; somebody on foot / a hostile mob / a ground vehicle on the physical runway
 * occupies it, the paving past the displaced thresholds counts (AutoTower.Params.blocked, Rwy.ext); taxiway «А» waits under a landing
 * over the high end; no air-defence zone published (it would be saved and show every client the embassies he does not know); the dome's
 * warning to a pilot in the air; the embassy's air defence on the radio (embassyRadio).</li>
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
        /** Embassy airfield: the last refusal told (EmbassyAtcMath.REFUSE_*; -1 none) and when (ticks) - a guessed request is not told twice. */
        public int embRefused = EmbassyAtcMath.REFUSE_NONE;
        public long embRefusedAt = -100000;

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
    /** Embassy airfield: the virtual towers' Tuners' weather (key -> a K_WX reading), the paving past their runways' ends (key -> blocks). */
    private final Map<String, AirportDataSVA.Device> embWx = new HashMap<String, AirportDataSVA.Device>();
    private final Map<String, Double> embExt = new HashMap<String, Double>();
    /** Embassy airfield: the aircraft in the air over an airfield embassy's dome now (entity id -> the embassy's id). */
    private final Map<Integer, Integer> domeIn = new HashMap<Integer, Integer>();

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
        embWx.clear();
        embExt.clear();
        domeIn.clear();
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
            if (EmbassyAirportSVA.isVirtual(t.key)) {
                if (!t.online(now)) {
                    towerLog(t.key).tick(now);                      // review 2026-10-27: asleep (nobody within ATC_RANGE) - nothing to derive
                    continue;                                       // for anyone; its first pass online does it all again
                }
                embassyDerived(w, d, t, now);                       // embassy airfield: the Tuners' weather, the paving past the ends
            }
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

    /** The weather station of a tower's airport (null none); an embassy's virtual tower without one near - the Tuners' own reading. */
    static AirportDataSVA.Device weather(AirportDataSVA d, AirportDataSVA.Tower t) {
        double[] c = d.centre(t);
        AirportDataSVA.Device wx = d.weatherNear(t.dim, c[0], c[1]);
        return wx != null || !EmbassyAirportSVA.isVirtual(t.key) ? wx : INSTANCE.embWx.get(t.key);
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
        if (taxiLightsOf(d, t)) {
            o.rmk |= AtisMath.RMK_FOLLOW;
        }
        if (t.ltRwy == 0 && x.night) {
            o.rmk |= AtisMath.RMK_LIGHTS_OFF;
        }
        EmbassyFieldSVA f = embassyField(w, t);
        if (f != null) {
            o.rmk |= AtisMath.embassyRemarks(f.full(), f.kind == EmbassyCompatSVA.SHORT, ConfigSVA.aptEmbassyLinerShort);
        }
        return o;
    }

    /**
     * The tower's airport has taxi lights of its own (ATIS «РУЛЕНИЕ ПО ЗЕЛЁНЫМ ОГНЯМ») - review 2026-10-27: a node of the dimension's taxi
     * graph within TowerMath.RANGE of its centre, a player's tower the players' lights only, an embassy's its own embassy's only (before:
     * any node of the dimension - the first embassy with a field turned the remark on at every player's tower).
     */
    @SuppressWarnings("unchecked")
    static boolean taxiLightsOf(AirportDataSVA d, AirportDataSVA.Tower t) {
        List<TaxiGraph.Node> nodes = (List<TaxiGraph.Node>) d.taxi(t.dim)[0];
        int emb = EmbassyAirportSVA.embassyOf(t.key);
        double[] c = d.centre(t);
        double r2 = TowerMath.RANGE * TowerMath.RANGE;
        for (TaxiGraph.Node n : nodes) {
            if (EmbassyAirportSVA.embassyOf(n.key) == emb && (n.x + 0.5 - c[0]) * (n.x + 0.5 - c[0]) + (n.z + 0.5 - c[1]) * (n.z + 0.5 - c[1]) <= r2) {
                return true;
            }
        }
        return false;
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

    /**
     * The «#key» arguments the ATIS (the letter) and the CPDLC (a report; an embassy's refusal reason and its air defence's line - Cpdlc.args)
     * put in - translations without arguments of their own.
     */
    public static boolean keyArg(String a) {
        return a != null && (a.startsWith("#sva.atis.letter.") || a.startsWith("#sva.cpdlc.rep.") || a.startsWith("#sva.cpdlc.refused.")
                || a.startsWith("#sva.cpdlc.embAd."));
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
        if (EmbassyAirportSVA.isVirtual(t.key)) {
            // embassy airfield: never published - the air defence's data is saved and synced to every client (the embassies a player does
            // not know would show); the codes' «Аэродром» is IffServerSVA.zoneOf, the dome's warning a line to the pilot (domeWarn)
            if (zonesSent.remove(t.key) != null) {
                ad.removeKp(ZONE_KEY + t.key);
            }
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
        embWx.remove(key);
        embExt.remove(key);
        for (Session s : sessions.values()) {
            if (s.tower.equals(key)) {
                s.tower = "";
            }
        }
    }

    // ---------------------------------------------------------------- the sessions

    /** The online tower nearest (x, z) within ATC_RANGE (null none). */
    static AirportDataSVA.Tower nearestTower(AirportDataSVA d, int dim, double x, double z, long now) {
        return nearestTower(d, dim, x, z, now, null);
    }

    /**
     * The online tower nearest (x, z) within ATC_RANGE for a crew knowing the embassies `knows` (null: every tower) - review 2026-10-27:
     * an embassy's virtual tower only for a crew that knows that embassy (EmbassyAirportSVA.knownBy - D_SYNC and the MCDU hide it from the
     * rest; its code would show as the MCDU's ATC unit and take the session of the player's own tower).
     */
    static AirportDataSVA.Tower nearestTower(AirportDataSVA d, int dim, double x, double z, long now, Set<Integer> knows) {
        AirportDataSVA.Tower best = null;
        double bd = TowerMath.ATC_RANGE * TowerMath.ATC_RANGE;
        for (AirportDataSVA.Tower t : d.towers()) {
            if (t.dim != dim || !t.online(now)) {
                continue;
            }
            int emb = knows == null ? -1 : EmbassyAirportSVA.embassyOf(t.key);
            if (emb >= 0 && !knows.contains(Integer.valueOf(emb))) {
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

    /**
     * The embassies (ids) any player aboard `v` knows (EmbassyAirportSVA.knownBy; a FakePlayer is nobody); null - no player aboard (an
     * unmanned machine on its autopilot: nobody to see its datalink, every tower as before).
     */
    static Set<Integer> crewKnows(EntityVehicleSVA v) {
        Set<Integer> out = null;
        for (Entity e : v.riders()) {
            if (e instanceof EntityPlayer && !(e instanceof FakePlayer)) {
                if (out == null) {
                    out = new HashSet<Integer>();
                }
                out.addAll(EmbassyAirportSVA.INSTANCE.knownBy((EntityPlayer) e));
            }
        }
        return out;
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
            domeWarn(w, d, v);                                      // embassy airfield: over a dome in the air - told once per entry
            Session s = sessions.get(id);
            AirportDataSVA.Tower t = nearestTower(d, dim, v.posX, v.posZ, now, crewKnows(v));
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
            // ATIS in the chat: once per letter, not more often than ATIS_GAP (an embassy's: only near its field or bound for it - atisReaches)
            Integer heard = s.atisHeard.get(t.key);
            if (ConfigSVA.aptAtisChat && t.letter >= 0 && t.atisText.length() > 0 && (heard == null || heard.intValue() != t.letter)
                    && now - s.atisAt >= ATIS_GAP && atisReaches(d, t, v)) {
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
        AutoTower.Rwy rw = useRwyExt(t);
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
        int emb = EmbassyAirportSVA.embassyOf(t.key);
        if (emb >= 0) {
            // embassy airfield: everybody's but the embassy's «Враг» at the controls - no trust list (the players' air defence does not
            // take these codes for «свой» anyway: AirportCodeMath.friendOf)
            if (embassyEnemy(v, emb)) {
                return -1;
            }
        } else if (!explicit && !com.sva.airdefense.AirportCodeMath.mayAssign(t.owner, t.access, t.sqTrust, v.getOwnerName())) {
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
        int lo = emb >= 0 ? EmbassyAtcMath.CODE_LO : t.codeLo, hi = emb >= 0 ? EmbassyAtcMath.CODE_HI : t.codeHi;
        int code = com.sva.airdefense.AirportCodeMath.assign(lo, hi, used, keep);
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
            EmbassyFieldSVA f = embassyField(w, t);
            if (f != null) {
                embassyScreen(w, d, t, f, now);                     // embassy airfield: its refusals before the rules see the requests
            }
            AutoTower.Rwy rw = useRwyExt(t);
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
            if (f != null) {
                p.blocked = embassyBlocked(w, f, rw.r.elev);
                p.taxiHold = landsOverHigh(f, rw);
            }
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
        // review 2026-10-27: the players' towers own the players' stop bars, an embassy's stop bar is its own tower's (no N x N search)
        List<AirportDataSVA.Tower> ts = new ArrayList<AirportDataSVA.Tower>();
        for (AirportDataSVA.Tower t : towersOf(d, dim)) {
            if (!EmbassyAirportSVA.isVirtual(t.key)) {
                ts.add(t);
            }
        }
        Map<String, Integer> nodeIx = null;
        for (Session s : sessions.values()) {
            AirportDataSVA.Tower t = s.tower.length() == 0 ? null : d.tower(s.tower);
            if (s.route == null || t == null || t.dim != dim || !t.online(now)) {
                continue;
            }
            if (nodeIx == null) {
                nodeIx = TaxiGraph.indexOf(nodes);
            }
            List<Integer> r = new ArrayList<Integer>();
            for (String k : s.route) {
                Integer i = nodeIx.get(k);
                if (i != null) {
                    r.add(i);
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
            int emb = EmbassyAirportSVA.embassyOf(dv.key);
            String ek = emb < 0 ? null : EmbassyAirportSVA.INSTANCE.towerKey(emb);
            AirportDataSVA.Tower t = emb < 0 ? towerOfLight(ts, dv.x, dv.z) : ek == null ? null : d.tower(ek);
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

    // ---------------------------------------------------------------- embassy airfield (docs/plan-embassy-airfield-addon.md §3.2)

    /** The airfield of an embassy's virtual tower (null: a player's tower, the embassies off). */
    static EmbassyFieldSVA embassyField(World w, AirportDataSVA.Tower t) {
        int id = EmbassyAirportSVA.embassyOf(t.key);
        return id < 0 ? null : EmbassyCompatSVA.field(w, id);
    }

    /** Embassy `id`'s virtual tower (null none). */
    static AirportDataSVA.Tower towerOfEmbassy(AirportDataSVA d, int id) {
        if (d == null || id < 0) {
            return null;
        }
        String k = EmbassyAirportSVA.INSTANCE.towerKey(id);
        AirportDataSVA.Tower t = k == null ? null : d.tower(k);
        if (t != null) {
            return t;
        }
        for (AirportDataSVA.Tower o : d.towers()) {
            if (EmbassyAirportSVA.embassyOf(o.key) == id) {
                return o;
            }
        }
        return null;
    }

    /** The ICAO embassy `id`'s radio speaks with: its tower's, else the core's proposal ("" none; EmbassyAdSVA's log too). */
    public static String embassyIcao(World w, AirportDataSVA d, int id) {
        AirportDataSVA.Tower t = towerOfEmbassy(d, id);
        if (t != null && t.icao.length() > 0) {
            return t.icao;
        }
        EmbassyFieldSVA f = EmbassyCompatSVA.field(w, id);
        return f == null ? "" : f.icao;
    }

    /**
     * A virtual tower's derived bits (every TOWER_TICKS, before its runway in use is picked): the paving past its runway's ends
     * (AutoTower.Rwy.ext - FULL 20) and the Tuners' own weather reading (the windsock's wind, the QNH lagged as a station's, the
     * temperature) - weather() answers it when no player's station is near, so the ATIS and the runway in use go by the wind.
     */
    void embassyDerived(World w, AirportDataSVA d, AirportDataSVA.Tower t, long now) {
        EmbassyFieldSVA f = embassyField(w, t);
        if (f == null) {
            embExt.remove(t.key);
            embWx.remove(t.key);
            return;
        }
        embExt.put(t.key, Double.valueOf(Math.max(EmbassyAtcMath.ext(f.end(true), f.thresholdKey(true)),
                EmbassyAtcMath.ext(f.end(false), f.thresholdKey(false)))));
        AirportDataSVA.Device dv = embWx.get(t.key);
        if (dv == null) {
            dv = new AirportDataSVA.Device();
            dv.key = t.key;
            dv.kind = AirportDataSVA.K_WX;
            embWx.put(t.key, dv);
        }
        double[] c = d.centre(t);
        dv.dim = t.dim;
        dv.x = c[0];
        dv.z = c[1];
        dv.y = f.floorY + 1;
        RvrMath.Wx x = AirportDataSVA.wx(w, dv);
        long seed = AirportDataSVA.windSeed(w);
        dv.qnh = WindField.lag(dv.qnh, WindField.qnhTarget(seed, now, x.rain, x.thunder), TOWER_TICKS);
        WindField.Wind wd = WindField.wind(seed, now, x.rain, x.thunder, AirportDataSVA.WX_HEIGHT);
        dv.windFrom = wd.from;
        dv.windSpeed = wd.speed;
        dv.windGust = wd.gust;
        BiomeGenBase b = w.getBiomeGenForCoords((int) Math.floor(dv.x), (int) Math.floor(dv.z));
        dv.temp = WindField.temperature(dv.y, b == null ? 0.8 : b.temperature, w.getWorldTime());
    }

    /** The runway in use with an embassy's paving past its ends (AutoTower.Rwy.ext); a player's - useRwy. */
    AutoTower.Rwy useRwyExt(AirportDataSVA.Tower t) {
        AutoTower.Rwy rw = useRwy(t);
        Double e = rw == null ? null : embExt.get(t.key);
        return e == null ? rw : new AutoTower.Rwy(rw.r, rw.dir, e.doubleValue());
    }

    /** An embassy tower's ATIS reaches `v`'s chat (EmbassyAtcMath.atisToChat: near its field or its MCDU's destination); a player's - yes. */
    static boolean atisReaches(AirportDataSVA d, AirportDataSVA.Tower t, EntityVehicleSVA v) {
        if (!EmbassyAirportSVA.isVirtual(t.key)) {
            return true;
        }
        double[] c = d.centre(t);
        com.sva.vehicle.LinerSystems L = v.state.liner();
        boolean dest = L != null && t.icao.length() > 0 && t.icao.equalsIgnoreCase(L.fms.to);
        return EmbassyAtcMath.atisToChat(Math.hypot(v.posX - c[0], v.posZ - c[1]), dest, ConfigSVA.aptEmbassyAtisRange);
    }

    /**
     * The player at the controls: the driver, the liner's first officer's seat too (null: nobody; never a FakePlayer). Public (review
     * 2026-10-27): the embassy's air defence (its 7700 pass) and its services (the «Лётная книжка») ask the same seats.
     */
    public static EntityPlayer pilotOf(EntityVehicleSVA v) {
        if (v.riddenByEntity instanceof EntityPlayer && !(v.riddenByEntity instanceof FakePlayer)) {
            return (EntityPlayer) v.riddenByEntity;
        }
        if (v.getVehicleType() == VehicleType.LINER) {
            for (Entity e : v.riders()) {
                if (e instanceof EntityPlayer && !(e instanceof FakePlayer) && LinerLogicSVA.pilot(v, (EntityPlayer) e)) {
                    return (EntityPlayer) e;
                }
            }
        }
        return null;
    }

    /** The embassy's «Враг» flies it (its pilot's level at embassy `id`; nobody at the controls - no). */
    static boolean embassyEnemy(EntityVehicleSVA v, int id) {
        EntityPlayer p = pilotOf(v);
        return p != null && EmbassyCompatSVA.levelOf(p, id) == EmbassyCompatSVA.ENEMY;
    }

    /** It squawks 7700 (with power): an embassy refuses it nothing. */
    static boolean squawksEmergency(EntityVehicleSVA v) {
        return AirDefenseSVA.xpdrOn(v) && v.state.xpdr.code == XpdrMath.EMERGENCY;
    }

    /** On the final of either end of the tower's runways (the dome's refusal leaves an approach to the runway alone). */
    static boolean onAnyFinal(AirportDataSVA.Tower t, AutoTower.Ac a) {
        for (IlsPairing.Rwy r : t.rwys) {
            if (AutoTower.onFinal(new AutoTower.Rwy(r, 1), a) || AutoTower.onFinal(new AutoTower.Rwy(r, -1), a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The embassy's refusals (EmbassyAtcMath.screen) to the requests of the aircraft talking to its tower, before AutoTower sees them:
     * the «Враг» at the controls - ОБСЛУЖИВАНИЕ ЗАПРЕЩЕНО to a landing, taxi or take-off; a landing asked over the dome - ПОСАДКА В КУПОЛЕ
     * ЗАПРЕЩЕНА; the liner asking to land on SHORT - refused (airport.embassyLinerShort); 7700 - nothing.
     */
    void embassyScreen(World w, AirportDataSVA d, AirportDataSVA.Tower t, EmbassyFieldSVA f, long now) {
        for (Session s : sessionsOf(t.key)) {
            AutoTower.Ac a = s.ac;
            boolean landing = a.req == AutoTower.R_LAND && a.st != AutoTower.S_LAND && a.st != AutoTower.S_GOAROUND;
            boolean ground = a.req == AutoTower.R_TAXI && a.st != AutoTower.S_TAXI || a.req == AutoTower.R_TAKEOFF && a.st != AutoTower.S_TAKEOFF;
            if (now - s.seen > SESSION_TICKS * 3 || !landing && !ground) {
                continue;
            }
            Entity e = w.getEntityByID(s.id);
            if (!(e instanceof EntityVehicleSVA)) {
                continue;
            }
            EntityVehicleSVA v = (EntityVehicleSVA) e;
            int r = EmbassyAtcMath.screen(landing, embassyEnemy(v, f.id), squawksEmergency(v), v.getVehicleType() == VehicleType.LINER,
                    f.kind == EmbassyCompatSVA.SHORT, ConfigSVA.aptEmbassyLinerShort, f.inDome(a.x, a.z), landing && onAnyFinal(t, a));
            if (r != EmbassyAtcMath.REFUSE_NONE) {
                refuse(w, d, t, s, r, now);
            }
        }
    }

    /**
     * The request dropped and answered (U_REFUSED with the reason, U_DOME): the MCDU's open request is closed by it; a guessed one (no
     * MCDU - guessed again every few ticks) is told again only after EmbassyAtcMath.REFUSE_GAP or for another reason.
     */
    void refuse(World w, AirportDataSVA d, AirportDataSVA.Tower t, Session s, int reason, long now) {
        AutoTower.Ac a = s.ac;
        int kind = a.req == AutoTower.R_LAND ? Cpdlc.D_LANDING : a.req == AutoTower.R_TAKEOFF ? Cpdlc.D_READY : Cpdlc.D_TAXI;
        Cpdlc.Msg asked = s.log.openRequest(kind);
        a.req = AutoTower.R_NONE;
        a.stbyTold = false;
        if (!EmbassyAtcMath.tellRefusal(asked != null, s.embRefused, s.embRefusedAt, reason, now, EmbassyAtcMath.REFUSE_GAP)) {
            return;
        }
        s.embRefused = reason;
        s.embRefusedAt = now;
        int ref = asked == null ? -1 : asked.id;
        if (reason == EmbassyAtcMath.REFUSE_DOME) {
            uplink(w, d, t, s, Cpdlc.U_DOME, 0, "", ref, now);
        } else {
            uplink(w, d, t, s, Cpdlc.U_REFUSED, 0, EmbassyAtcMath.REFUSE_KEYS[reason], ref, now);
        }
    }

    /** It flies and has a transponder: the towers' traffic (AirportDataSVA.aircraft) - on the runway it is AutoTower's own business. */
    static boolean traffic(EntityVehicleSVA v) {
        return v.getVehicleType().movement.flies && XpdrMath.fitted(v.getVehicleType());
    }

    /**
     * Somebody the traffic does not hold is on the embassy's paved runway (AutoTower.Params.blocked): a player on foot, a hostile mob, a
     * ground vehicle. Only the loaded chunks are looked at (World.getEntitiesWithinAABB).
     */
    static boolean embassyBlocked(World w, EmbassyFieldSVA f, double elev) {
        int[] b = f.runwayBox();
        if (b == null || b.length < 4) {
            return false;
        }
        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(b[0], elev - 1, b[1], b[2] + 1, elev + AutoTower.ON_RWY_H, b[3] + 1);
        for (Object o : w.getEntitiesWithinAABB(Entity.class, box)) {
            Entity e = (Entity) o;
            if (e.isDead) {
                continue;
            }
            if (e instanceof EntityPlayer ? e.ridingEntity == null : e instanceof IMob || e instanceof EntityVehicleSVA && !traffic((EntityVehicleSVA) e)) {
                return true;
            }
        }
        return false;
    }

    /** The runway in use lands over the field's HIGH end (FULL: taxiway «А» under its approach - the taxi hold, risk 2 of the core's plan). */
    static boolean landsOverHigh(EmbassyFieldSVA f, AutoTower.Rwy rw) {
        if (!f.full() || f.taxiBox() == null) {
            return false;
        }
        String high = EmbassyAirportMath.key(f.dim, f.thresholdKey(false));
        return high != null && high.equals(rw.r.end(rw.dir).key);
    }

    /**
     * An aircraft in the air entering an airfield embassy's dome cylinder: the pilot's line «ПОСАДКА В КУПОЛЕ ЗАПРЕЩЕНА» once per entry
     * (the same embassy's again not before ATIS_GAP) - no air-defence zone is published for the dome (zone()). Every SESSION_TICKS.
     */
    void domeWarn(World w, AirportDataSVA d, EntityVehicleSVA v) {
        Integer key = Integer.valueOf(v.getEntityId());
        int id = EmbassyCompatSVA.on() && v.airborneForAtc() ? EmbassyCompatSVA.domeAt(w, v.posX, v.posZ) : -1;
        if (id < 0) {
            domeIn.remove(key);
            return;
        }
        if (domeIn.size() > 512) {
            domeIn.clear();                                         // gone vehicles' entries: a new entry at worst
        }
        Integer was = domeIn.put(key, Integer.valueOf(id));
        EmbassyFieldSVA f = was != null && was.intValue() == id ? null : EmbassyCompatSVA.field(w, id);
        if (f == null) {
            return;                                                 // still over it / an embassy without an airfield
        }
        AirDefenseSVA.INSTANCE.tellPilot(v, "embDome" + id, ATIS_GAP, 0xF0B040, f.hasRunway() ? "sva.emb.dome.warn" : "sva.emb.dome.warnHeli",
                embassyIcao(w, d, id));                             // review 2026-10-27: a HELI field has no runway - its line says «Н»
    }

    /** The virtual tower's ATIS as the chat line (the Dispatcher's tab: IEmbassyAirHookSC.atisLine through EmbassyAtcSVA); null none / off. */
    public static IChatComponent embassyAtis(World w, int id) {
        AirportDataSVA d = w == null || w.isRemote ? null : AirportDataSVA.get(w);
        AirportDataSVA.Tower t = towerOfEmbassy(d, id);
        if (t == null || t.letter < 0 || t.atisText.length() == 0 || !t.online(w.getTotalWorldTime())) {
            return null;
        }
        return atisChat(t);
    }

    /**
     * The embassy's air defence on its tower's radio: Cpdlc.U_EMB_AD (arg - the line EmbassyCompatSVA.AD_*, s - the callsign; no response
     * wanted) into the vehicle's session log (the liner's MCDU shows it, its chime comes with the unread count) and the tower's. No chat /
     * HUD here - EmbassyAdSVA's own message brings them. Nothing without a session (no tower in reach).
     */
    public void embassyRadio(World w, EntityVehicleSVA v, int embassyId, int adLine, String callsign) {
        Session s = w == null || v == null ? null : session(v.getEntityId());
        if (s == null || adLine < 0 || adLine >= EmbassyCompatSVA.AD_KEYS.length) {
            return;
        }
        AirportDataSVA d = AirportDataSVA.get(w);
        AirportDataSVA.Tower t = towerOfEmbassy(d, embassyId);
        long now = w.getTotalWorldTime();
        Cpdlc.Msg m = s.log.make(true, Cpdlc.U_EMB_AD, adLine, callsign == null || callsign.length() == 0 ? s.ac.call : callsign, "", -1, now);
        m.tower = t != null ? t.icao : embassyIcao(w, d, embassyId);
        m.call = s.ac.call;
        m.aircraft = s.id;
        if (!s.mcdu) {
            m.read = true;                                          // no MCDU to read it: the chat line came with the air defence's
        }
        s.log.add(m);
        if (t != null) {
            towerLog(t.key).add(m.copy());
        }
        System.out.println("[SVA-ATC] embassy AD " + m.tower + " -> " + m.call + ": " + Cpdlc.key(m) + " " + java.util.Arrays.toString(Cpdlc.args(m)));
    }
}
