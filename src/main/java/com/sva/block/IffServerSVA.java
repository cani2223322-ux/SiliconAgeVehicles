package com.sva.block;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.AirportCodeMath;
import com.sva.airdefense.IffMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.airport.Cpdlc;
import com.sva.entity.EntityVehicleSVA;

import net.minecraft.world.World;

/**
 * Wave A (plan-next §9 A10, mock-up veh_ad_impl_a_3_tower_iff): the towers' IFF on the server - every ROUND ticks (from
 * AtcServerSVA's tick, airdefense.iff) each ONLINE tower interrogates the aircraft its radars see near its airport (the
 * airport's owner's radars within AtcServerSVA.RADAR_LINK of the tower, and the tracks of his command posts within
 * TowerMath.TRAFFIC_RANGE - «the tower and the КП interrogate»): no reply, a code the tower gave another aircraft, a code of
 * its range it gave nobody - IffMath's track runs: the tower's alarm (its screen, a tone at the tower) and the call to the
 * pilot (the chat + the HUD line; a liner also the CPDLC uplink «ОТВЕТЧИК НЕ ОТВЕЧАЕТ...» - any answer to it is the pilot's
 * answer), the reminder or (airdefense.iffHandoff) the handoff to the air defence (AirDefenseSVA.handoff: the friendly
 * posts classify it a violator, their rules decide). The transponder switched on - resolved, the handoff taken back.
 * Audit 2026-10-22: a transponder without the board's power (or a wreck's) does not answer; the tower's owner's and his
 * access list's aircraft are not interrogated (no alarm on the owner's own); a handoff is kept by the vehicle's persistent
 * id and this tower (AirDefenseDataSVA - it survives a restart, two towers no longer overwrite each other).
 */
public final class IffServerSVA {

    public static final IffServerSVA INSTANCE = new IffServerSVA();
    /** Interrogation rounds (ticks); a handoff lasts this long unless renewed (ticks). */
    public static final int ROUND = 20, HANDOFF_HOLD = 2400;

    /** "towerKey|entityId" -> its IFF track. */
    private final Map<String, IffMath.Track> tracks = new LinkedHashMap<String, IffMath.Track>();

    private IffServerSVA() {
    }

    public void clear() {
        tracks.clear();
    }

    /** The IFF track of an aircraft at a tower (null none). */
    public IffMath.Track track(String towerKey, int id) {
        return tracks.get(towerKey + "|" + id);
    }

    /** The worst state of an aircraft at any tower (IffMath.CLEAR none) - the command post's mark. */
    public int stateOf(int id) {
        int st = IffMath.CLEAR;
        String tail = "|" + id;
        for (Map.Entry<String, IffMath.Track> e : tracks.entrySet()) {
            if (e.getKey().endsWith(tail)) {
                int s = e.getValue().state;
                st = s == IffMath.HANDED || st == IffMath.HANDED ? IffMath.HANDED : Math.max(st, s == IffMath.ANSWERED ? IffMath.SUSPECT : s);
            }
        }
        return st;
    }

    /** The tower's alarm: an intruder warned / handed there now. */
    public boolean alarm(String towerKey) {
        for (Map.Entry<String, IffMath.Track> e : tracks.entrySet()) {
            if (e.getKey().startsWith(towerKey + "|") && IffMath.intruder(e.getValue().state)) {
                return true;
            }
        }
        return false;
    }

    /** The pilot answered the tower by datalink (any response / downlink). */
    public void answer(World w, int id, String towerKey) {
        IffMath.Track t = track(towerKey, id);
        if (t != null && IffMath.answer(t)) {
            AirDefenseSVA.INSTANCE.release(w, t.vid, towerKey);
        }
    }

    /** The tower's airport zone (as published; a default one when the air-defence zone is off). */
    static ZoneMath.Zone zoneOf(AirportDataSVA.Tower t) {
        return com.sva.airport.TowerMath.zone(t.icao, t.name.length() > 0 ? t.name : t.icao, t.owner, t.dim, t.x, t.y, t.z, t.rwys, t.codeLo, t.codeHi);
    }

    /** One round for a dimension (AtcServerSVA's tick). */
    public void tick(World w, AirportDataSVA d, long now) {
        if (!ConfigSVA.adIff || now % ROUND != 7) {
            return;
        }
        int dim = w.provider.dimensionId;
        Set<String> seenKeys = new HashSet<String>();
        for (AirportDataSVA.Tower t : AtcServerSVA.towersOf(d, dim)) {
            if (!t.online(now) || t.icao.length() != 4) {
                continue;
            }
            round(w, t, zoneOf(t), now, seenKeys);
        }
        // tracks of aircraft no longer near / seen: forgotten (their handoff taken back)
        for (Iterator<Map.Entry<String, IffMath.Track>> it = tracks.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, IffMath.Track> e = it.next();
            if (seenKeys.contains(e.getKey())) {
                continue;
            }
            String k = e.getKey();
            if (!isDim(d, k, dim)) {
                continue;
            }
            if (e.getValue().state == IffMath.HANDED) {
                AirDefenseSVA.INSTANCE.release(w, e.getValue().vid, k.substring(0, k.lastIndexOf('|')));
            }
            it.remove();
        }
    }

    private static boolean isDim(AirportDataSVA d, String trackKey, int dim) {
        AirportDataSVA.Tower t = d.tower(trackKey.substring(0, trackKey.lastIndexOf('|')));
        return t == null || t.dim == dim;
    }

    /** What the tower's radars (and its owner's posts) see near the airport: entity id -> the vehicle. */
    static Map<Integer, EntityVehicleSVA> seen(World w, AirportDataSVA.Tower t, ZoneMath.Zone apt) {
        Map<Integer, EntityVehicleSVA> out = new LinkedHashMap<Integer, EntityVehicleSVA>();
        for (TileEntityRadarSVA r : AirDefenseSVA.INSTANCE.loaded(w, TileEntityRadarSVA.class)) {
            if (!r.isPowered() || !r.getOwnerName().equalsIgnoreCase(t.owner)
                    || r.getDistanceFrom(t.x + 0.5, t.y + 0.5, t.z + 0.5) > AtcServerSVA.RADAR_LINK * AtcServerSVA.RADAR_LINK) {
                continue;
            }
            for (AirDefenseSVA.Contact c : r.contacts().values()) {
                add(out, c, apt);
            }
        }
        double far = com.sva.airport.TowerMath.TRAFFIC_RANGE;
        for (TileEntityCommandPostSVA k : AirDefenseSVA.INSTANCE.loaded(w, TileEntityCommandPostSVA.class)) {
            if (!k.isPowered() || !k.getOwnerName().equalsIgnoreCase(t.owner) || k.getDistanceFrom(t.x + 0.5, t.y + 0.5, t.z + 0.5) > far * far) {
                continue;
            }
            for (AirDefenseSVA.Contact c : k.tracks().values()) {
                add(out, c, apt);
            }
        }
        return out;
    }

    private static void add(Map<Integer, EntityVehicleSVA> out, AirDefenseSVA.Contact c, ZoneMath.Zone apt) {
        if (c.v == null || c.v.isDead || out.containsKey(Integer.valueOf(c.id))) {
            return;
        }
        if (AirportCodeMath.nearAirport(apt, c.v.posX, c.v.posY, c.v.posZ, true)) {
            out.put(Integer.valueOf(c.id), c.v);
        }
    }

    private void round(World w, AirportDataSVA.Tower t, ZoneMath.Zone apt, long now, Set<String> seenKeys) {
        // the codes this tower gave: code -> the aircraft
        Map<Integer, Integer> given = new HashMap<Integer, Integer>();
        for (AtcServerSVA.Session s : AtcServerSVA.INSTANCE.allSessions()) {
            if (s.sqTower.equals(t.key) && s.sqCode >= 0) {
                given.put(Integer.valueOf(s.sqCode), Integer.valueOf(s.id));
            }
        }
        int response = Math.max(1, ConfigSVA.adIffResponseSeconds) * 20;
        for (Map.Entry<Integer, EntityVehicleSVA> e : seen(w, t, apt).entrySet()) {
            EntityVehicleSVA v = e.getValue();
            int id = e.getKey().intValue();
            String vid = AirDefenseSVA.vehKey(v);
            if (com.sva.airdefense.AirportCodeMath.mayAssign(t.owner, t.access, null, v.getOwnerName())) {
                continue;                                           // the tower's owner's / his access list's own aircraft
            }
            XpdrMath.Xpdr x = v.state.xpdr;
            boolean replied = AirDefenseSVA.xpdrOn(v);
            if (replied) {
                x.lastInterrogated = now;                           // the ОТВЕТ lamp: the tower's interrogator too
            }
            int code = replied ? x.code : -1;
            Integer to = code >= 0 ? given.get(Integer.valueOf(code)) : null;
            boolean here = to != null && to.intValue() == id, other = to != null && to.intValue() != id;
            boolean inRange = code >= 0 && code >= t.codeLo && code <= t.codeHi;
            int reason = IffMath.reason(replied, code, inRange, here, other);
            String key = t.key + "|" + id;
            IffMath.Track tr = tracks.get(key);
            if (tr == null) {
                if (reason == IffMath.R_NONE) {
                    AirDefenseSVA.INSTANCE.release(w, vid, t.key);  // a handoff kept over a restart, the reason gone
                    continue;
                }
                tr = new IffMath.Track();
                tracks.put(key, tr);
            }
            tr.vid = vid;
            seenKeys.add(key);
            int act = IffMath.step(tr, reason, now, IffMath.CONFIRM, response, ConfigSVA.adIffHandoff);
            AirDefenseSVA.LangKey why = new AirDefenseSVA.LangKey("sva.iff.reason." + IffMath.REASON_KEYS[reason]);   // audit review: an explicit key
            switch (act) {
                case IffMath.A_WARN:
                    w.playSoundEffect(t.x + 0.5, t.y + 6, t.z + 0.5, "note.bassattack", 2F, 0.6F);
                    // audit 2026-10-22 (7.3): «передаём ПВО» only when the handoff is on; the reason as it is (not always «без ответчика»)
                    AirDefenseSVA.INSTANCE.tellPilot(v, "iff" + t.key, 0, 0xF05050, ConfigSVA.adIffHandoff ? "sva.iff.msg.call" : "sva.iff.msg.callNoHandoff",
                            t.icao, why, String.valueOf(ConfigSVA.adIffResponseSeconds));
                    datalink(w, t, id, now);
                    System.out.println("[SVA-ATC] IFF " + t.icao + ": intruder " + AtcServerSVA.callsign(v) + " (" + IffMath.REASON_KEYS[reason] + ")");
                    break;
                case IffMath.A_REMIND:
                    AirDefenseSVA.INSTANCE.tellPilot(v, "iffR" + t.key, 0, 0xF05050, "sva.iff.msg.remind", t.icao, why);
                    break;
                case IffMath.A_HANDOFF:
                    AirDefenseSVA.INSTANCE.handoff(w, vid, t.key, t.owner, t.icao, now + HANDOFF_HOLD);
                    AirDefenseSVA.INSTANCE.tellPilot(v, "iffH" + t.key, 0, 0xF05050, "sva.iff.msg.handoff", t.icao);
                    AirDefenseSVA.INSTANCE.launchLog("IFF " + t.icao + ": " + AtcServerSVA.callsign(v) + " (" + v.getOwnerName() + ", "
                            + IffMath.REASON_KEYS[reason] + ") handed to the air defence");
                    break;
                case IffMath.A_RESOLVED:
                    AirDefenseSVA.INSTANCE.release(w, vid, t.key);
                    AirDefenseSVA.INSTANCE.tellPilot(v, "iffOk" + t.key, 0, 0x62E06A, "sva.iff.msg.resolved", t.icao);
                    break;
                default:
                    if (tr.state == IffMath.HANDED) {
                        AirDefenseSVA.INSTANCE.handoff(w, vid, t.key, t.owner, t.icao, now + HANDOFF_HOLD);      // renewed while it lasts
                    }
                    break;
            }
            if (tr.state == IffMath.CLEAR) {
                tracks.remove(key);
            }
        }
    }

    /** A liner talking to the tower also gets the call by datalink (its answer is the pilot's answer). */
    private static void datalink(World w, AirportDataSVA.Tower t, int id, long now) {
        AtcServerSVA.Session s = AtcServerSVA.INSTANCE.session(id);
        AirportDataSVA d = AirportDataSVA.get(w);
        if (s == null || d == null || !s.mcdu || !s.tower.equals(t.key)) {
            return;
        }
        AtcServerSVA.INSTANCE.uplink(w, d, t, s, Cpdlc.U_IFF, 0, "", -1, now);
    }

    /** The world test: every IFF track (read only). */
    public Map<String, IffMath.Track> tracks() {
        return tracks;
    }

    /** A vehicle's entries dropped at once and its handoffs taken back (the world test; a round drops them anyway). */
    public void forget(EntityVehicleSVA v) {
        int id = v.getEntityId();
        for (Iterator<String> it = tracks.keySet().iterator(); it.hasNext();) {
            if (it.next().endsWith("|" + id)) {
                it.remove();
            }
        }
        AirDefenseSVA.INSTANCE.release(v.worldObj, AirDefenseSVA.vehKey(v), null);
    }


    /** The intruders at a tower: entity id -> state (the tower's screen). */
    public Map<Integer, Integer> intruders(String towerKey) {
        Map<Integer, Integer> out = new HashMap<Integer, Integer>();
        String head = towerKey + "|";
        for (Map.Entry<String, IffMath.Track> e : tracks.entrySet()) {
            if (e.getKey().startsWith(head)) {
                out.put(Integer.valueOf(Integer.parseInt(e.getKey().substring(head.length()))), Integer.valueOf(e.getValue().state));
            }
        }
        return out;
    }


    /** The intruder list for a tower's screen (id, state, reason, seconds to the handoff). */
    public List<int[]> list(String towerKey, long now) {
        List<int[]> out = new java.util.ArrayList<int[]>();
        String head = towerKey + "|";
        int response = Math.max(1, ConfigSVA.adIffResponseSeconds) * 20;
        for (Map.Entry<String, IffMath.Track> e : tracks.entrySet()) {
            if (e.getKey().startsWith(head)) {
                IffMath.Track t = e.getValue();
                out.add(new int[] {Integer.parseInt(e.getKey().substring(head.length())), t.state, t.reason, IffMath.secondsLeft(t, now, response)});
            }
        }
        return out;
    }
}
