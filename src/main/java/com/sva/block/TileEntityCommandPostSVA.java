package com.sva.block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.sc.energy.Tier;
import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.BallisticsMath;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.entity.EntityVehicleSVA;
import com.sva.item.ItemCodeCardSVA;
import com.sva.net.NetADSVA;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/**
 * Air-defence wave (mock-up veh_ad_4_command): the command post («КП», MV, 32 EU/t). Every 10 ticks it merges what the
 * radars of its owner within BallisticsMath.LINK see into one track per vehicle (the most exact position: tracking >
 * height finder > surveillance; the altitude from a tracking / height radar or a Mode C / S answer), numbers the tracks,
 * classifies them (RoeMath.classify: the owner's / friends' vehicles, today's code from the «ключ дня» secret, the own
 * codes, the Mode S white list, «свой на 10 минут», 7700 / 7600, the forbidden zones it holds) and writes the log: zone
 * entries, IDENT, 7700, yesterday's code, a new day, the launchers' locks and launches. A violator in a zone with «тревога
 * при входе» raises the alarm (redstone 15 on every side, a message to the owner and his access list, off by the button
 * or after 60 s without violators). Its launchers on «по отметкам КП» fire only at what the operator assigned (or, with
 * «Авто», at violators). Its zones go to AirDefenseDataSVA (the pilots' copies).
 * The screen (client.GuiCommandPostSVA, no container) gets the picture every 10 ticks while open.
 */
public class TileEntityCommandPostSVA extends TileEntityAirDefenseSVA {

    public static final int UPKEEP = 32, MAX_CODES = 8, MAX_WHITE = 16, LOG_LINES = 40, ALARM_HOLD = 1200;
    private static final Random RND = new Random();

    private String name = "", secret = "";
    private boolean dayKey = true, auto, alarm, alarmManual;
    private final int[] codes = {-1, -1, -1, -1, -1, -1, -1, -1};
    private final List<Integer> whiteBoard = new ArrayList<Integer>();
    private final List<String> whiteOwner = new ArrayList<String>();
    private final List<ZoneMath.Zone> zones = new ArrayList<ZoneMath.Zone>();
    private final LinkedList<String[]> log = new LinkedList<String[]>();
    // not saved
    private final Map<Integer, AirDefenseSVA.Contact> tracks = new LinkedHashMap<Integer, AirDefenseSVA.Contact>();
    private final Map<Integer, Long> grants = new HashMap<Integer, Long>();
    private final Map<Integer, String> assigned = new HashMap<Integer, String>();
    private final Map<Integer, Object[]> engaged = new HashMap<Integer, Object[]>();
    private final Map<EntityPlayer, Long> viewers = new HashMap<EntityPlayer, Long>();
    private List<TileEntityRadarSVA> radars = new ArrayList<TileEntityRadarSVA>();
    private List<TileEntityLauncherSVA> launchers = new ArrayList<TileEntityLauncherSVA>();
    private int timer, nextNo = 1;
    private long lastDay = -1, lastViolator;
    private boolean zonesPublished;

    public TileEntityCommandPostSVA() {
        super(Tier.MV);
    }

    @Override
    public int upkeep() {
        return UPKEEP;
    }

    @Override
    public int nodeKind() {
        return AirDefenseDataSVA.N_KP;
    }

    @Override
    public String nameKey() {
        return "sva.ad.kp";
    }

    public String name() {
        return name.length() == 0 ? "KP" : name;
    }

    public boolean isAlarm() {
        return alarm && powered;
    }

    public boolean isAuto() {
        return auto;
    }

    public List<ZoneMath.Zone> zones() {
        return zones;
    }

    public Map<Integer, AirDefenseSVA.Contact> tracks() {
        return tracks;
    }

    public List<TileEntityLauncherSVA> launchers() {
        return launchers;
    }

    public List<TileEntityRadarSVA> radars() {
        return radars;
    }

    /** Today's code (-1: the day key is off here or on the server). */
    public int dayCode() {
        if (!ConfigSVA.adDayKey || !dayKey || worldObj == null) {
            return -1;
        }
        return XpdrMath.dayCode(secret, XpdrMath.day(worldObj.getWorldTime()));
    }

    // ------------------------------------------------------------------ the work

    @Override
    protected void serverTick() {
        if (secret.length() == 0) {
            secret = randomSecret();
            markDirty();
        }
        if (!zonesPublished) {
            zonesPublished = true;
            publishZones();
        }
        if (++timer % 10 != 0) {
            return;
        }
        long now = worldObj.getTotalWorldTime();
        if (timer % 40 == 0 || radars.isEmpty() && launchers.isEmpty()) {
            relink();
        }
        if (!powered) {
            tracks.clear();
            setAlarm(false, false);
            sendViewers();
            return;
        }
        long day = XpdrMath.day(worldObj.getWorldTime());
        if (day != lastDay) {
            if (lastDay >= 0 && dayCode() >= 0) {
                log("sva.ad.log.newDay", String.valueOf(day), XpdrMath.format(dayCode()));
            }
            lastDay = day;
        }
        merge(now);
        for (Iterator<Map.Entry<Integer, Long>> it = grants.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue().longValue() < now) {
                it.remove();
            }
        }
        for (Iterator<Map.Entry<Integer, Object[]>> it = engaged.entrySet().iterator(); it.hasNext();) {
            if (((Long) it.next().getValue()[3]).longValue() < now - 20) {
                it.remove();
            }
        }
        if (alarm && !alarmManual && now - lastViolator > ALARM_HOLD) {
            setAlarm(false, false);
        }
        sendViewers();
    }

    private void relink() {
        radars = new ArrayList<TileEntityRadarSVA>();
        for (TileEntityRadarSVA r : AirDefenseSVA.INSTANCE.loaded(worldObj, TileEntityRadarSVA.class)) {
            if (r.getOwnerName().equalsIgnoreCase(ownerName) && r.getDistanceFrom(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) <= BallisticsMath.LINK * BallisticsMath.LINK) {
                radars.add(r);
            }
        }
        launchers = new ArrayList<TileEntityLauncherSVA>();
        for (TileEntityLauncherSVA l : AirDefenseSVA.INSTANCE.loaded(worldObj, TileEntityLauncherSVA.class)) {
            if (l.getOwnerName().equalsIgnoreCase(ownerName) && l.getDistanceFrom(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) <= BallisticsMath.LINK * BallisticsMath.LINK) {
                launchers.add(l);
            }
        }
        Comparator<TileEntityAirDefenseSVA> byPos = new Comparator<TileEntityAirDefenseSVA>() {
            @Override
            public int compare(TileEntityAirDefenseSVA a, TileEntityAirDefenseSVA b) {
                return a.xCoord != b.xCoord ? a.xCoord - b.xCoord : a.zCoord != b.zCoord ? a.zCoord - b.zCoord : a.yCoord - b.yCoord;
            }
        };
        Collections.sort(radars, byPos);
        Collections.sort(launchers, byPos);
    }

    /** The world test: link now. */
    public void relinkNow() {
        relink();
    }

    /** The launcher's name on this post: «ПУ-1»... (1-based; 0 - not linked). */
    public int launcherNo(TileEntityLauncherSVA l) {
        return launchers.indexOf(l) + 1;
    }

    /**
     * The radars' pictures into the tracks (rebuilt each time from the radars' last scans: which kinds see it now, the
     * most exact position, the altitude), then classes and events. A track nobody sees keeps its last place until
     * RadarMath.TRACK_TIMEOUT.
     */
    private void merge(long now) {
        Map<Integer, Integer> rank = new HashMap<Integer, Integer>();
        for (AirDefenseSVA.Contact t : tracks.values()) {
            t.kinds = 0;
        }
        for (TileEntityRadarSVA r : radars) {
            if (r.isInvalid() || !r.isPowered()) {
                continue;
            }
            for (AirDefenseSVA.Contact c : r.contacts().values()) {
                int k = Integer.numberOfTrailingZeros(c.kinds);
                if (c.v == null || c.v.isDead || !RadarMath.validKind(k) || now - c.seen > RadarMath.INTERVAL[k] + 20) {
                    continue;
                }
                AirDefenseSVA.Contact t = tracks.get(Integer.valueOf(c.id));
                if (t == null) {
                    t = new AirDefenseSVA.Contact(c.id);
                    t.no = nextNo;
                    nextNo = nextNo % 99 + 1;
                    tracks.put(Integer.valueOf(c.id), t);
                }
                Integer was = rank.get(Integer.valueOf(c.id));
                if (was == null) {
                    t.altKnown = false;
                }
                int rk = k == RadarMath.TRACK ? 3 : k == RadarMath.HEIGHT ? 2 : 1;
                if (was == null || rk >= was.intValue()) {
                    rank.put(Integer.valueOf(c.id), Integer.valueOf(rk));
                    t.x = c.x;
                    t.z = c.z;
                }
                if (c.altKnown && (k != RadarMath.SURVEY || !t.altKnown)) {
                    t.y = c.y;
                    t.altKnown = true;
                }
                t.kinds |= c.kinds;
                t.v = c.v;
                t.seen = Math.max(t.seen, c.seen);
                t.owner = c.owner;
                t.type = c.type;
                t.manned = c.manned;
                t.vx = c.vx;
                t.vy = c.vy;
                t.vz = c.vz;
                if (k == RadarMath.SURVEY) {
                    t.replied = c.replied;
                    t.mode = c.mode;
                    t.code = c.code;
                    t.board = c.board;
                    t.ident = c.ident;
                }
            }
        }
        long today = XpdrMath.day(worldObj.getWorldTime());
        int dc = dayCode(), yc = dc >= 0 ? XpdrMath.dayCode(secret, today - 1) : -1;
        for (Iterator<AirDefenseSVA.Contact> it = tracks.values().iterator(); it.hasNext();) {
            AirDefenseSVA.Contact t = it.next();
            if (t.v == null || t.v.isDead || now - t.seen > RadarMath.TRACK_TIMEOUT) {
                it.remove();
                assigned.remove(Integer.valueOf(t.id));
                continue;
            }
            classify(t, now, dc, yc);
        }
    }

    private void classify(AirDefenseSVA.Contact t, long now, int dc, int yc) {
        EntityVehicleSVA v = t.v;
        t.rel = relation(t.owner);
        boolean ownFriend = t.rel == 1 || t.rel == 2 && !ConfigSVA.adFireOnFriends;
        boolean ownCode = t.replied && (t.code == dc && dc >= 0 || ownCode(t.code));
        if (t.replied && !ownCode) {
            for (ZoneMath.Zone z : zones) {
                if (ZoneMath.aerodromeCode(z, t.code) && ZoneMath.contains(z, t.x, t.y, t.z, t.altKnown)) {
                    ownCode = true;
                }
            }
        }
        boolean white = t.replied && XpdrMath.givesBoard(t.mode) && whitelisted(t.board, t.owner);
        Long g = grants.get(Integer.valueOf(t.id));
        boolean granted = g != null && g.longValue() > now;
        boolean grace = t.replied && v.state.xpdr.noRadioGrace(now);
        ZoneMath.Zone viol = ZoneMath.violated(zones, t.x, t.y, t.z, t.course(), t.altKnown);
        ZoneMath.Zone restr = ZoneMath.inside(zones, ZoneMath.RESTRICTED, t.x, t.y, t.z, t.altKnown);
        t.cls = RoeMath.classify(ownFriend, t.replied, t.code, ownCode, white, granted, grace, viol != null);
        String who = t.board > 0 ? "B-" + t.board : String.format("%02d", Integer.valueOf(t.no));
        // events
        String zn = viol == null ? "" : viol.name;
        if (!zn.equals(t.zone)) {
            if (viol != null && t.cls == RoeMath.VIOLATOR) {
                log("sva.ad.log.zoneIn", String.format("%02d", Integer.valueOf(t.no)), viol.name);
                AirDefenseSVA.INSTANCE.tellPilot(v, "zone" + viol.name, 600, 0xF05050, "sva.ad.msg.forbidden", name(), viol.name,
                        String.format("%03d", Integer.valueOf(ZoneMath.exitCourse(viol, t.x, t.z))));
                if (viol.alarm) {
                    setAlarm(true, false);
                    if (alarm) {
                        alarmMessage(viol.name);
                    }
                }
            }
            t.zone = zn;
        }
        if (t.cls == RoeMath.VIOLATOR) {
            lastViolator = now;
        }
        String rn = restr == null || t.cls == RoeMath.OWN || t.cls == RoeMath.FRIEND ? "" : restr.name;
        if (!rn.equals(t.restricted)) {
            if (rn.length() > 0) {
                log("sva.ad.log.restrictedIn", String.format("%02d", Integer.valueOf(t.no)), rn);
                AirDefenseSVA.INSTANCE.tellPilot(v, "restr" + rn, 600, 0xF0B040, "sva.ad.msg.restricted", name(), rn);
            }
            t.restricted = rn;
        }
        if (t.ident && !t.saidIdent) {
            log("sva.ad.log.ident", who);
        }
        t.saidIdent = t.ident;
        boolean em = t.replied && t.code == XpdrMath.EMERGENCY;
        if (em && !t.said7700) {
            log("sva.ad.log.emergency", who);
            worldObj.playSoundEffect(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, "note.pling", 1F, 0.6F);
        }
        t.said7700 = em;
        boolean old = t.replied && yc >= 0 && t.code == yc && t.code != dc;
        if (old && !t.saidOld) {
            log("sva.ad.log.oldCode", String.format("%02d", Integer.valueOf(t.no)), XpdrMath.format(t.code));
        }
        t.saidOld = old;
        String a = assigned.get(Integer.valueOf(t.id));
        t.assigned = "";
        if (a != null) {
            for (TileEntityLauncherSVA l : launchers) {
                if (l.key().equals(a)) {
                    t.assigned = String.valueOf(launcherNo(l));
                }
            }
        }
        Object[] en = engaged.get(Integer.valueOf(t.id));
        t.stage = en == null ? RoeMath.ST_NONE : ((Integer) en[1]).intValue();
        t.toFire = en == null ? 0 : ((Integer) en[2]).intValue();
        t.engagedBy = en == null ? "" : (String) en[0];
    }

    public boolean ownCode(int code) {
        if (!XpdrMath.valid(code)) {
            return false;
        }
        for (int c : codes) {
            if (c == code) {
                return true;
            }
        }
        return false;
    }

    public boolean whitelisted(int board, String owner) {
        for (int i = 0; i < whiteBoard.size(); i++) {
            if (whiteBoard.get(i).intValue() == board && (whiteOwner.get(i).length() == 0 || whiteOwner.get(i).equalsIgnoreCase(owner))) {
                return true;
            }
        }
        return false;
    }

    /** The tracking radars' order: what the launchers engage / were assigned first, then violators, then foreigners. */
    public List<Integer> lockPriority() {
        List<Integer> out = new ArrayList<Integer>();
        for (Integer id : engaged.keySet()) {
            out.add(id);
        }
        for (Integer id : assigned.keySet()) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (AirDefenseSVA.Contact t : tracks.values()) {
                if (t.cls == (pass == 0 ? RoeMath.VIOLATOR : RoeMath.FOREIGN) && !out.contains(Integer.valueOf(t.id))) {
                    out.add(Integer.valueOf(t.id));
                }
            }
        }
        return out;
    }

    /** Was the track assigned to this launcher by the operator? */
    public boolean assignedTo(int id, TileEntityLauncherSVA l) {
        String a = assigned.get(Integer.valueOf(id));
        return a != null && a.equals(l.key());
    }

    /** A launcher reports its engagement of a track (every 5 ticks while it lasts). */
    public void reportEngaged(int id, TileEntityLauncherSVA l, int stage, int toFire) {
        engaged.put(Integer.valueOf(id), new Object[] {String.valueOf(launcherNo(l)), Integer.valueOf(stage), Integer.valueOf(toFire),
                Long.valueOf(worldObj.getTotalWorldTime())});
    }

    // ------------------------------------------------------------------ the log, the alarm

    /** A log line: the time, a lang key and its arguments (the screen translates; the launch log file gets the key). */
    public void log(String key, String... args) {
        String[] e = new String[2 + args.length];
        e[0] = clock(worldObj == null ? 0 : worldObj.getWorldTime());
        e[1] = key;
        System.arraycopy(args, 0, e, 2, args.length);
        log.addFirst(e);
        while (log.size() > LOG_LINES) {
            log.removeLast();
        }
        markDirty();
    }

    /** The log, newest first (world tests). */
    public List<String[]> logLines() {
        return log;
    }

    /** Game time as "06:42" (world time 0 = 06:00). */
    public static String clock(long worldTime) {
        long t = (worldTime % 24000 + 24000) % 24000;
        return String.format("%02d:%02d", Long.valueOf((t / 1000 + 6) % 24), Long.valueOf(t % 1000 * 60 / 1000));
    }

    public void setAlarm(boolean on, boolean manual) {
        if (on == alarm) {
            alarmManual = on && (manual || alarmManual);
            return;
        }
        alarm = on;
        alarmManual = on && manual;
        if (on) {
            lastViolator = worldObj.getTotalWorldTime();
            log("sva.ad.log.alarm");
            worldObj.playSoundEffect(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, "note.bassattack", 2F, 0.5F);
        } else {
            log("sva.ad.log.alarmOff");
        }
        worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord, getBlockType());
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);       // the screen's red front
        markDirty();
    }

    // ------------------------------------------------------------------ sync: the alarm's front

    private boolean clientAlarm;

    public boolean clientAlarm() {
        return clientAlarm;
    }

    @Override
    public net.minecraft.network.Packet getDescriptionPacket() {
        NBTTagCompound t = new NBTTagCompound();
        t.setBoolean("A", alarm);
        return new net.minecraft.network.play.server.S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, t);
    }

    @Override
    public void onDataPacket(net.minecraft.network.NetworkManager net, net.minecraft.network.play.server.S35PacketUpdateTileEntity pkt) {
        boolean was = clientAlarm;
        clientAlarm = pkt.func_148857_g().getBoolean("A");
        if (was != clientAlarm && worldObj != null) {
            worldObj.markBlockRangeForRenderUpdate(xCoord, yCoord, zCoord, xCoord, yCoord, zCoord);
        }
    }

    private void alarmMessage(String zone) {
        for (Object o : worldObj.playerEntities) {
            if (o instanceof EntityPlayerMP && relation(((EntityPlayerMP) o).getCommandSenderName()) != 0) {
                AirDefenseSVA.INSTANCE.tell((EntityPlayerMP) o, 0xF05050, "sva.ad.msg.alarm", name(), zone);
            }
        }
    }

    // ------------------------------------------------------------------ zones

    private void publishZones() {
        AirDefenseDataSVA d = AirDefenseDataSVA.get(worldObj);
        if (d == null) {
            return;
        }
        for (ZoneMath.Zone z : zones) {
            z.kp = name();
            z.owner = ownerName;
            z.kx = xCoord;
            z.ky = yCoord;
            z.kz = zCoord;
            z.dim = worldObj.provider.dimensionId;
        }
        d.setZones(key(), zones);
    }

    /** The block was broken: its zones go too. */
    @Override
    public void removed() {
        super.removed();
        AirDefenseDataSVA d = AirDefenseDataSVA.get(worldObj);
        if (d != null) {
            d.removeKp(key());
        }
    }

    /** World tests: replace the zones. */
    public void setZones(List<ZoneMath.Zone> list) {
        zones.clear();
        for (ZoneMath.Zone z : list) {
            if (zones.size() < ZoneMath.MAX_ZONES) {
                zones.add(z.copy());
            }
        }
        publishZones();
        markDirty();
    }

    // ------------------------------------------------------------------ the operator's commands

    public void command(EntityPlayerMP p, NBTTagCompound d) {
        String op = d.getString("Op");
        long now = worldObj.getTotalWorldTime();
        Integer id = Integer.valueOf(d.getInteger("Id"));
        AirDefenseSVA.Contact t = tracks.get(id);
        if ("assign".equals(op) && t != null) {
            String l = d.getString("L");
            TileEntityLauncherSVA chosen = null;
            if ("auto".equals(l)) {
                double best = Double.MAX_VALUE;
                for (TileEntityLauncherSVA x : launchers) {
                    double dd = x.getDistanceFrom(t.x, t.y, t.z);
                    if (x.isPowered() && dd < best && Math.sqrt(dd) <= x.maxRange()) {
                        best = dd;
                        chosen = x;
                    }
                }
            } else {
                for (TileEntityLauncherSVA x : launchers) {
                    if (x.key().equals(l)) {
                        chosen = x;
                    }
                }
            }
            if (chosen != null) {
                assigned.put(id, chosen.key());
                log("sva.ad.log.assigned", String.format("%02d", Integer.valueOf(t.no)), String.valueOf(launcherNo(chosen)));
            }
        } else if ("track".equals(op) && t != null) {
            assigned.remove(id);
            log("sva.ad.log.trackOnly", String.format("%02d", Integer.valueOf(t.no)));
        } else if ("grant".equals(op) && t != null) {
            grants.put(id, Long.valueOf(now + RoeMath.GRANT_TICKS));
            assigned.remove(id);
            log("sva.ad.log.grant", String.format("%02d", Integer.valueOf(t.no)));
        } else if ("alarm".equals(op)) {
            setAlarm(!alarm, true);
            if (alarm) {
                alarmMessage("-");
            }
        } else if ("auto".equals(op)) {
            auto = !auto;
            markDirty();
        } else if ("msg".equals(op) && t != null && t.v != null) {
            String text = Text.clean(d.getString("T"), 80);
            if (text.length() > 0) {
                String to = t.board > 0 ? "B-" + t.board : t.replied ? XpdrMath.format(t.code) : String.format("%02d", Integer.valueOf(t.no));
                AirDefenseSVA.INSTANCE.tellPilot(t.v, "op" + now, 0, 0xF0B040, "sva.ad.msg.operator", name(), to, text);
                log("sva.ad.log.msg", to, text);
            }
        } else if ("print".equals(op)) {
            printFromPaper(p);
        } else if ("zone".equals(op)) {
            ZoneMath.Zone z = new ZoneMath.Zone();
            z.read(d.getCompoundTag("Z"));
            int i = d.getInteger("Idx");
            if (z.name.length() == 0) {
                z.name = "Z" + (zones.size() + 1);
            }
            if (i >= 0 && i < zones.size()) {
                zones.set(i, z);
            } else if (zones.size() < ZoneMath.MAX_ZONES) {
                zones.add(z);
            }
            publishZones();
            markDirty();
        } else if ("zoneDel".equals(op)) {
            int i = d.getInteger("Idx");
            if (i >= 0 && i < zones.size()) {
                zones.remove(i);
                publishZones();
                markDirty();
            }
        } else if ("set".equals(op)) {
            settings(p, d);
        } else if ("secret".equals(op) && isOwner(p)) {
            String s = Text.clean(d.getString("S"), 32);
            secret = s.length() == 0 ? randomSecret() : s;
            log("sva.ad.log.secret", XpdrMath.format(dayCode() < 0 ? 0 : dayCode()));
            markDirty();
        } else if ("open".equals(op)) {
            addViewer(p);
        }
        sendViewers();
    }

    private void settings(EntityPlayerMP p, NBTTagCompound d) {
        name = Text.clean(d.getString("Name"), 24);
        dayKey = d.getBoolean("DayKey");
        int[] c = d.getIntArray("Codes");
        for (int i = 0; i < MAX_CODES; i++) {
            codes[i] = i < c.length && XpdrMath.valid(c[i]) ? c[i] : -1;
        }
        int[] wb = d.getIntArray("WB");
        NBTTagList wo = d.getTagList("WO", 8);
        whiteBoard.clear();
        whiteOwner.clear();
        for (int i = 0; i < wb.length && i < MAX_WHITE; i++) {
            if (wb[i] > 0 && wb[i] < 100) {
                whiteBoard.add(Integer.valueOf(wb[i]));
                whiteOwner.add(i < wo.tagCount() ? Text.clean(wo.getStringTagAt(i), 32) : "");
            }
        }
        if (isOwner(p) && d.hasKey("Access")) {
            NBTTagList al = d.getTagList("Access", 8);
            List<String> names = new ArrayList<String>();
            for (int i = 0; i < al.tagCount(); i++) {
                names.add(Text.clean(al.getStringTagAt(i), 32));
            }
            setAccess(names);
        }
        publishZones();
        markDirty();
    }

    /** Right-click with a code card: today's code printed on it (the day key on). @return printed */
    public boolean printCard(EntityPlayer p, ItemStack card) {
        int dc = dayCode();
        if (dc < 0 || !powered) {
            return false;
        }
        ItemCodeCardSVA.print(card, name(), XpdrMath.day(worldObj.getWorldTime()), dc);
        return true;
    }

    /** World tests: today's code on a card whatever the power. */
    public void printCardForTest(ItemStack card) {
        ItemCodeCardSVA.print(card, name(), XpdrMath.day(worldObj.getWorldTime()), XpdrMath.dayCode(secret, XpdrMath.day(worldObj.getWorldTime())));
    }

    /** «Карты кодов: печать»: a sheet of paper from the operator's inventory becomes today's card. */
    private void printFromPaper(EntityPlayerMP p) {
        int dc = dayCode();
        if (dc < 0 || !powered) {
            AirDefenseSVA.INSTANCE.tell(p, 0xF0B040, "sva.ad.msg.noDayKey");
            return;
        }
        if (!p.capabilities.isCreativeMode && !p.inventory.consumeInventoryItem(Items.paper)) {
            AirDefenseSVA.INSTANCE.tell(p, 0xF0B040, "sva.ad.msg.noPaper");
            return;
        }
        ItemStack s = new ItemStack(com.sva.init.ModContentSVA.codeCard);
        ItemCodeCardSVA.print(s, name(), XpdrMath.day(worldObj.getWorldTime()), dc);
        if (!p.inventory.addItemStackToInventory(s)) {
            p.dropPlayerItemWithRandomChoice(s, false);
        }
        p.inventoryContainer.detectAndSendChanges();
        log("sva.ad.log.printed", p.getCommandSenderName());
    }

    private static String randomSecret() {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            b.append(abc.charAt(RND.nextInt(abc.length())));
        }
        return b.toString();
    }

    /** World tests: a known secret / own codes / white list / the day key. */
    public void debugSetup(String secret, int[] own, int whiteBoardNo, String whiteOwnerName) {
        this.secret = secret;
        dayKey = true;
        for (int i = 0; i < MAX_CODES; i++) {
            codes[i] = own != null && i < own.length ? own[i] : -1;
        }
        whiteBoard.clear();
        whiteOwner.clear();
        if (whiteBoardNo > 0) {
            whiteBoard.add(Integer.valueOf(whiteBoardNo));
            whiteOwner.add(whiteOwnerName == null ? "" : whiteOwnerName);
        }
    }

    /** World tests: «свой на 10 минут» / an assignment / «Авто» without the screen. */
    public void debugGrant(int id) {
        grants.put(Integer.valueOf(id), Long.valueOf(worldObj.getTotalWorldTime() + RoeMath.GRANT_TICKS));
    }

    public void debugAssign(int id, TileEntityLauncherSVA l) {
        assigned.put(Integer.valueOf(id), l.key());
    }

    // ------------------------------------------------------------------ the screen

    public void addViewer(EntityPlayer p) {
        viewers.put(p, Long.valueOf(worldObj.getTotalWorldTime()));
    }

    public void removeViewer(EntityPlayer p) {
        viewers.remove(p);
    }

    private void sendViewers() {
        if (viewers.isEmpty()) {
            return;
        }
        NBTTagCompound snap = null;
        for (Iterator<EntityPlayer> it = viewers.keySet().iterator(); it.hasNext();) {
            EntityPlayer p = it.next();
            if (!(p instanceof EntityPlayerMP) || p.isDead || p.worldObj != worldObj || p.getDistanceSq(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) > 144) {
                it.remove();
                continue;
            }
            if (snap == null) {
                snap = snapshot();
            }
            snap.setBoolean("Owner", isOwner(p));
            NetADSVA.send((EntityPlayerMP) p, NetADSVA.D_KP, snap);
        }
    }

    /** The picture for the screen. */
    public NBTTagCompound snapshot() {
        NBTTagCompound t = new NBTTagCompound();
        t.setIntArray("Pos", new int[] {xCoord, yCoord, zCoord});
        t.setString("Name", name);
        t.setString("Own", ownerName);
        long day = XpdrMath.day(worldObj.getWorldTime());
        t.setLong("Day", day);
        t.setString("Time", clock(worldObj.getWorldTime()));
        t.setInteger("DayCode", dayCode());
        t.setBoolean("DayKey", dayKey);
        t.setBoolean("Alarm", alarm);
        t.setBoolean("Auto", auto);
        t.setBoolean("Powered", powered);
        t.setInteger("Energy", getEnergyStored());
        t.setInteger("EMax", getMaxEnergyStored());
        t.setString("Mode", ConfigSVA.adFires() ? "fire" : ConfigSVA.adEnabled ? "warn" : "off");
        NBTTagList rl = new NBTTagList();
        for (TileEntityRadarSVA r : radars) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("X", r.xCoord);
            c.setInteger("Z", r.zCoord);
            c.setByte("K", (byte) r.kind());
            c.setBoolean("P", r.isPowered() && !r.isInvalid());
            rl.appendTag(c);
        }
        t.setTag("Radars", rl);
        NBTTagList ll = new NBTTagList();
        for (TileEntityLauncherSVA l : launchers) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("X", l.xCoord);
            c.setInteger("Z", l.zCoord);
            c.setString("Key", l.key());
            c.setByte("K", (byte) l.kind());
            c.setByte("Roe", (byte) l.roe());
            c.setInteger("Ammo", l.ammo());
            c.setInteger("AmmoMax", l.ammoMax());
            c.setByte("E", (byte) Math.round(100.0 * l.getEnergyStored() / Math.max(1, l.getMaxEnergyStored())));
            c.setBoolean("P", l.isPowered() && !l.isInvalid());
            c.setInteger("R", (int) l.maxRange());
            int tgt = 0, st = 0;
            for (AirDefenseSVA.Contact tr : tracks.values()) {
                if (tr.engagedBy.equals(String.valueOf(launcherNo(l))) && tr.stage > st) {
                    st = tr.stage;
                    tgt = tr.no;
                }
            }
            c.setInteger("T", tgt);
            c.setByte("St", (byte) st);
            ll.appendTag(c);
        }
        t.setTag("Launchers", ll);
        NBTTagList tl = new NBTTagList();
        long now = worldObj.getTotalWorldTime();
        for (AirDefenseSVA.Contact tr : tracks.values()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("No", tr.no);
            c.setInteger("Id", tr.id);
            c.setDouble("X", tr.x);
            c.setDouble("Y", tr.y);
            c.setDouble("Z", tr.z);
            c.setBoolean("Alt", tr.altKnown);
            c.setInteger("Code", tr.replied ? tr.code : -1);
            c.setByte("Mode", (byte) tr.mode);
            c.setInteger("Board", tr.board);
            c.setByte("Type", (byte) tr.type);
            c.setInteger("Spd", (int) Math.round(tr.speedKmh()));
            c.setInteger("Crs", (int) Math.round(tr.course()) % 360);
            c.setByte("Cls", (byte) tr.cls);
            c.setString("Zone", tr.zone.length() > 0 ? tr.zone : tr.restricted);
            c.setByte("Seen", (byte) tr.kinds);
            c.setString("Asg", tr.assigned);
            Long g = grants.get(Integer.valueOf(tr.id));
            c.setInteger("Grant", g == null ? 0 : (int) Math.max(0, (g.longValue() - now) / 20));
            c.setBoolean("Ident", tr.ident);
            c.setByte("St", (byte) tr.stage);
            c.setInteger("Tf", tr.toFire);
            c.setString("By", tr.engagedBy);
            c.setBoolean("Old", tr.saidOld);
            tl.appendTag(c);
        }
        t.setTag("Tracks", tl);
        NBTTagList zl = new NBTTagList();
        for (ZoneMath.Zone z : zones) {
            NBTTagCompound c = new NBTTagCompound();
            z.write(c);
            zl.appendTag(c);
        }
        t.setTag("Zones", zl);
        NBTTagList lg = new NBTTagList();
        for (String[] e : log) {
            NBTTagList line = new NBTTagList();
            for (String s : e) {
                line.appendTag(new NBTTagString(s));
            }
            NBTTagCompound c = new NBTTagCompound();
            c.setTag("L", line);
            lg.appendTag(c);
        }
        t.setTag("Log", lg);
        t.setIntArray("Codes", codes.clone());
        int[] wb = new int[whiteBoard.size()];
        NBTTagList wo = new NBTTagList();
        for (int i = 0; i < wb.length; i++) {
            wb[i] = whiteBoard.get(i).intValue();
            wo.appendTag(new NBTTagString(whiteOwner.get(i)));
        }
        t.setIntArray("WB", wb);
        t.setTag("WO", wo);
        NBTTagList al = new NBTTagList();
        for (String a : access) {
            al.appendTag(new NBTTagString(a));
        }
        t.setTag("Access", al);
        return t;
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public void readFromNBT(NBTTagCompound t) {
        super.readFromNBT(t);
        name = t.getString("KpName");
        secret = t.getString("KpSecret");
        dayKey = !t.hasKey("KpDayKey") || t.getBoolean("KpDayKey");
        auto = t.getBoolean("KpAuto");
        alarm = t.getBoolean("KpAlarm");
        alarmManual = t.getBoolean("KpAlarmManual");
        int[] c = t.getIntArray("KpCodes");
        for (int i = 0; i < MAX_CODES; i++) {
            codes[i] = i < c.length && XpdrMath.valid(c[i]) ? c[i] : -1;
        }
        whiteBoard.clear();
        whiteOwner.clear();
        int[] wb = t.getIntArray("KpWB");
        NBTTagList wo = t.getTagList("KpWO", 8);
        for (int i = 0; i < wb.length && i < MAX_WHITE; i++) {
            whiteBoard.add(Integer.valueOf(wb[i]));
            whiteOwner.add(i < wo.tagCount() ? wo.getStringTagAt(i) : "");
        }
        zones.clear();
        NBTTagList zl = t.getTagList("KpZones", 10);
        for (int i = 0; i < zl.tagCount() && i < ZoneMath.MAX_ZONES; i++) {
            ZoneMath.Zone z = new ZoneMath.Zone();
            z.read(zl.getCompoundTagAt(i));
            zones.add(z);
        }
        log.clear();
        NBTTagList lg = t.getTagList("KpLog", 10);
        for (int i = 0; i < lg.tagCount() && i < LOG_LINES; i++) {
            NBTTagList line = lg.getCompoundTagAt(i).getTagList("L", 8);
            String[] e = new String[line.tagCount()];
            for (int k = 0; k < e.length; k++) {
                e[k] = line.getStringTagAt(k);
            }
            if (e.length >= 2) {
                log.add(e);
            }
        }
        lastDay = t.hasKey("KpDay") ? t.getLong("KpDay") : -1;
        zonesPublished = false;
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        super.writeToNBT(t);
        t.setString("KpName", name);
        t.setString("KpSecret", secret);
        t.setBoolean("KpDayKey", dayKey);
        t.setBoolean("KpAuto", auto);
        t.setBoolean("KpAlarm", alarm);
        t.setBoolean("KpAlarmManual", alarmManual);
        t.setIntArray("KpCodes", codes.clone());
        int[] wb = new int[whiteBoard.size()];
        NBTTagList wo = new NBTTagList();
        for (int i = 0; i < wb.length; i++) {
            wb[i] = whiteBoard.get(i).intValue();
            wo.appendTag(new NBTTagString(whiteOwner.get(i)));
        }
        t.setIntArray("KpWB", wb);
        t.setTag("KpWO", wo);
        NBTTagList zl = new NBTTagList();
        for (ZoneMath.Zone z : zones) {
            NBTTagCompound c = new NBTTagCompound();
            z.write(c);
            zl.appendTag(c);
        }
        t.setTag("KpZones", zl);
        NBTTagList lg = new NBTTagList();
        for (String[] e : log) {
            NBTTagList line = new NBTTagList();
            for (String s : e) {
                line.appendTag(new NBTTagString(s));
            }
            NBTTagCompound c = new NBTTagCompound();
            c.setTag("L", line);
            lg.appendTag(c);
        }
        t.setTag("KpLog", lg);
        t.setLong("KpDay", lastDay);
    }

    /** Text from a screen: printable, no formatting codes, at most n characters. */
    static final class Text {
        private Text() {
        }

        static String clean(String s, int n) {
            if (s == null) {
                return "";
            }
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < s.length() && b.length() < n; i++) {
                char c = s.charAt(i);
                if (c >= ' ' && c != '§' && c != 0x7F) {
                    b.append(c);
                }
            }
            return b.toString().trim();
        }
    }
}
