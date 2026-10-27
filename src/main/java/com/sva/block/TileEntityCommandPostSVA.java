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
import com.sva.airdefense.AirportCodeMath;
import com.sva.airdefense.BallisticsMath;
import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.EmbassyAdSVA;
import com.sva.airdefense.NoRadioMath;
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
 * Upgrades wave: its «Оборудование» tab opens the equipment screen (a container: the energy, the input tier, the four
 * upgrade slots - transformers, the universal one, storage, economizers; it has no work interval to overclock).
 * Audit 2026-10-22 (exploits and hardening): the commands come rate-limited per player (AirDefenseSVA.cmdAllowed) and no
 * longer send the picture themselves - the screens get it with the regular send every 10 ticks, which also drops a viewer
 * who may no longer use the post (taken off the access list: he stops seeing the day code; his screen is closed). A zone
 * must lie within airdefense.zoneMaxDistance of the post, and a new one fit airdefense.maxZonesPerPlayer.
 * Audit 2026-10-22 (the friend / foe trust model):
 * <ul>
 * <li>its radars and launchers are those LINKED to it (AirDefenseSVA.linkedPost - one post each, bug 19): only its own
 * launchers get its assignments, «Авто», holds and grants;</li>
 * <li>the day code comes from the owner's one secret (AirDefenseDataSVA, bug 18: every post of his gives the same; an older
 * save's first post hands its own over); its settings are mirrored into its trust data (AirDefenseDataSVA.KpInfo) for its
 * launchers while it is off or unloaded (bug 2);</li>
 * <li>the operator's decisions - «свой на 10 минут», «только вести» (now a real hold-fire for its launchers), the
 * assignments, the 7600 decisions and episodes (with «враждебный», bug 8) - are kept by the vehicle's persistent id with
 * an absolute expiry in the trust data, saved (bug 20); assignments and holds last while the track does (+ KEEP);</li>
 * <li>the IFF handoff and the zone violation are worked out before an aerodrome code, which counts only when the tower gave
 * it to this very aircraft and the tower is trusted by its ICAO code bound to its owner (bugs 6, 7);</li>
 * <li>a track no surveillance radar has interrogated is UNKNOWN (no fire) for a full surveillance cycle, then the post's own
 * interrogator asks it (bug 15); 7500 is logged and raises the alarm; the Mode S white list matches only with the owner.</li>
 * </ul>
 * Code-only wave (docs/proposal-ad-strict-iff.md, «Опознавание: ПО КОДУ»):
 * <ul>
 * <li>switched on by the owner (an op counts as one - the log and the owner are told) in two steps («Sure» from the
 * confirmation window), it takes effect airdefense.codeOnlyArmSeconds later - the countdown on the screen, the owner and the
 * access list told in the chat; off at once; never with the server's codeOnly=false or without the day key. Once armed
 * the tracks' miss counts and its launchers' engagements start over (item 16);</li>
 * <li>while on, classify() takes the strict branch (AirDefenseSVA.classifyByCode): only the key's code makes a friend
 * (whose vehicle it is no longer matters), a target after two misses a survey round apart; the log says «не опознан» with
 * the reason (the owner's / his friends' boards - also to the list in the chat, item 33), «верный код у чужого борта» (item
 * 30), the «ПОДБОР» block, 7700's grace and its end;</li>
 * <li>the owner's commands codeOnly / codeOpts / rekey («Сменить ключ»: every card printed before is void, the list told to
 * print new ones) / coAllRoe; the operator's 7700 decisions (emFriend / emCheck / emHostile, kept per owner with the grace -
 * NoRadioMath) and unlock (the «ПОДБОР» block lifted; «Свой» lifts it too); the picture carries the mode, the arming, the
 * options, the readiness line and each track's reason.</li>
 * </ul>
 */
public class TileEntityCommandPostSVA extends TileEntityAirDefenseSVA {

    public static final int UPKEEP = 32, MAX_CODES = 8, MAX_WHITE = 16, LOG_LINES = 40, ALARM_HOLD = 1200;
    /** Audit 2026-10-22: an assignment / «только вести» outlives its track this long (ticks) - a chunk reload or a restart keeps it. */
    public static final int KEEP = 1200;
    private static final Random RND = new Random();

    private String name = "", secret = "";
    private boolean dayKey = true, auto, alarm, alarmManual;
    private final int[] codes = {-1, -1, -1, -1, -1, -1, -1, -1};
    private final List<Integer> whiteBoard = new ArrayList<Integer>();
    private final List<String> whiteOwner = new ArrayList<String>();
    private final List<ZoneMath.Zone> zones = new ArrayList<ZoneMath.Zone>();
    private final LinkedList<String[]> log = new LinkedList<String[]>();
    /** Wave A: the airports this post trusts besides its owner's / friends' (ICAO codes or tower names, upper case). */
    private final List<String> trusted = new ArrayList<String>();
    // not saved (audit 2026-10-22: the 7600 entries, «свой на 10 минут», the assignments and holds live in the trust data, info())
    private final Map<Integer, AirDefenseSVA.Contact> tracks = new LinkedHashMap<Integer, AirDefenseSVA.Contact>();
    private final AirDefenseDataSVA.KpInfo scratch = new AirDefenseDataSVA.KpInfo();
    private final Map<Integer, Object[]> engaged = new HashMap<Integer, Object[]>();
    private final Map<EntityPlayer, Long> viewers = new HashMap<EntityPlayer, Long>();
    private List<TileEntityRadarSVA> radars = new ArrayList<TileEntityRadarSVA>();
    private List<TileEntityLauncherSVA> launchers = new ArrayList<TileEntityLauncherSVA>();
    private int timer, nextNo = 1;
    private long lastDay = -1, lastViolator;
    private boolean zonesPublished, infoPublished;
    /** Audit review 2026-10-22: loaded from a save older than the owner binding of the trusted / white list (migrate). */
    private boolean legacy;
    /** Audit review 2026-10-22: the last 7500 alarm per vehicle (vehId -> tick) - toggling 7500 / 7000 no longer floods. */
    private final Map<String, Long> hijackSaid = new HashMap<String, Long>();
    /** Code-only wave: the mode's state at the last round (CodeOnlyMath.CO_*; -1 not looked yet) - the arming's end is told once. */
    private int coLast = -1;

    public TileEntityCommandPostSVA() {
        super(Tier.MV);
    }

    @Override
    public int baseUpkeep() {
        return UPKEEP;
    }

    @Override
    public int upgradeBlock() {
        return com.sva.airdefense.AdUpgradeMath.B_KP;
    }

    @Override
    public NBTTagCompound equipSnapshot() {
        NBTTagCompound t = super.equipSnapshot();
        t.setString("Kp", name());
        t.setInteger("Radars", radars.size());
        t.setInteger("Launchers", launchers.size());
        t.setInteger("Tracks", tracks.size());
        return t;
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

    /**
     * Today's code (-1: the day key is off here or on the server). Code-only wave: the key period on airdefense.keyClock
     * (AirDefenseDataSVA.keyPeriod - the total world time by default), the server's pepper and the owner's key generation.
     */
    public int dayCode() {
        if (!ConfigSVA.adDayKey || !dayKey || worldObj == null) {
            return -1;
        }
        return keyCode(AirDefenseDataSVA.keyPeriod(worldObj));
    }

    /** Code-only wave: the owner's key code in a key period (-1: none; whatever the day key - the caller asks). */
    public int keyCode(long period) {
        String sec = ownerSecret();                                 // bug 18: made / handed over first
        AirDefenseDataSVA d = worldObj == null || worldObj.isRemote ? null : AirDefenseDataSVA.get(worldObj);
        return d == null ? -1 : sec.length() == 0 ? -1 : d.keyCode(ownerName, period);
    }

    /**
     * Audit 2026-10-22 (bug 18): the owner's one secret (every post of his gives the same day code - a guest's card from one
     * post is good at all of them); an older save's post hands its own secret over when the owner has none yet.
     */
    public String ownerSecret() {
        AirDefenseDataSVA d = worldObj == null || worldObj.isRemote ? null : AirDefenseDataSVA.get(worldObj);
        if (d == null) {
            return secret;
        }
        String s = d.ownerSecret(ownerName, secret, randomSecret());
        if (!s.equals(secret)) {
            secret = s;                                             // the block's copy (an older version reads it)
            markDirty();
        }
        return s;
    }

    /** Audit 2026-10-22: this post's trust data and the operator's decisions (AirDefenseDataSVA - saved; made when missing). */
    public AirDefenseDataSVA.KpInfo info() {
        AirDefenseDataSVA d = worldObj == null || worldObj.isRemote ? null : AirDefenseDataSVA.get(worldObj);
        return d == null ? scratch : d.info(key(), true);
    }

    private void infoChanged() {
        AirDefenseDataSVA d = worldObj == null ? null : AirDefenseDataSVA.get(worldObj);
        if (d != null) {
            d.markDirty();
        }
    }

    // ------------------------------------------------------------------ code-only wave: «Опознавание: ПО КОДУ»

    /** The mode's state on this post now (CodeOnlyMath.CO_*: off, arming, on, forbidden by the server, no day key). */
    public int codeOnlyState() {
        AirDefenseDataSVA.KpInfo i = info();
        return worldObj == null ? CodeOnlyMath.CO_OFF : CodeOnlyMath.state(i.codeOnly, i.armAt, worldObj.getTotalWorldTime(), ConfigSVA.adCodeOnly,
                dayKey && ConfigSVA.adDayKey);
    }

    /** The code-only classification applies now (on, armed, allowed, with the day key). */
    public boolean codeOnly() {
        return codeOnlyState() == CodeOnlyMath.CO_ON;
    }

    /**
     * Item 16: the owner switches the mode on (the countdown starts, the log, the owner and his list in the chat) or off (at
     * once). Not with the server's codeOnly=false, nor without the day key (the post's or the server's). An op on another's
     * post (item 34): the log names him, the owner is told. @return switched as asked
     */
    public boolean setCodeOnly(EntityPlayerMP p, boolean on) {
        AirDefenseDataSVA.KpInfo i = info();
        long now = worldObj.getTotalWorldTime();
        String who = p == null ? "-" : p.getCommandSenderName();
        if (on && !CodeOnlyMath.mayEnable(ConfigSVA.adCodeOnly, ConfigSVA.adDayKey, dayKey)) {
            if (p != null) {
                AirDefenseSVA.INSTANCE.tell(p, 0xF0B040, ConfigSVA.adCodeOnly ? "sva.ad.msg.coNoKey" : "sva.ad.msg.coBanned");
            }
            return false;
        }
        if (on == i.codeOnly) {
            return true;
        }
        i.codeOnly = on;
        i.armAt = on ? CodeOnlyMath.armAt(now, ConfigSVA.adCodeOnlyArmSeconds) : -1;
        coLast = on ? CodeOnlyMath.CO_ARMING : CodeOnlyMath.CO_OFF;  // the end of the arming is told by the next round
        infoChanged();
        AirDefenseDataSVA d = AirDefenseDataSVA.get(worldObj);
        if (d != null) {
            d.touchZones();                                         // the zones' «ПО КОДУ» mark on the pilots' screens
        }
        int secs = Math.max(0, ConfigSVA.adCodeOnlyArmSeconds);
        if (on) {
            log("sva.ad.log.coArming", who, String.valueOf(secs));
        } else {
            log("sva.ad.log.coOff", who);
        }
        for (EntityPlayerMP a : alarmAudience()) {
            if (on) {
                AirDefenseSVA.INSTANCE.tell(a, 0xF0B040, "sva.ad.msg.coArming", name(), Integer.valueOf(secs), who);
            } else {
                AirDefenseSVA.INSTANCE.tell(a, 0xF0B040, "sva.ad.msg.coOff", name(), who);
            }
        }
        if (p != null && !p.getCommandSenderName().equalsIgnoreCase(ownerName)) {
            log("sva.ad.log.coByOp", who);                          // item 34: an op on another's post
            for (EntityPlayerMP a : alarmAudience()) {
                if (a.getCommandSenderName().equalsIgnoreCase(ownerName)) {
                    AirDefenseSVA.INSTANCE.tell(a, 0xF05050, "sva.ad.msg.coByOp", name(), who);
                }
            }
        }
        AirDefenseSVA.INSTANCE.launchLog("KP " + name() + " (" + ownerName + "): code-only " + (on ? "armed, on in " + secs + " s" : "off") + " by " + who);
        return true;
    }

    /**
     * Item 16: the arming done - the log, the chat; the tracks' miss counts and the launchers' engagements start over. Review
     * 2026-10-27: a switched-on mode whose arming was dropped (the day key off, the server's switch off at the last start -
     * CodeOnlyMath.rearm) arms anew here: the countdown, the log and the chat as for the owner's switch.
     */
    private void codeOnlyRound() {
        int co = codeOnlyState();
        AirDefenseDataSVA.KpInfo i = info();
        if (CodeOnlyMath.rearm(i.codeOnly, i.armAt, co)) {
            int secs = Math.max(0, ConfigSVA.adCodeOnlyArmSeconds);
            i.armAt = CodeOnlyMath.armAt(worldObj.getTotalWorldTime(), secs);
            infoChanged();
            AirDefenseDataSVA dd = AirDefenseDataSVA.get(worldObj);
            if (dd != null) {
                dd.touchZones();                                    // the zones' «ПО КОДУ» mark on the pilots' screens
            }
            log("sva.ad.log.coRearm", String.valueOf(secs));
            for (EntityPlayerMP a : alarmAudience()) {
                AirDefenseSVA.INSTANCE.tell(a, 0xF0B040, "sva.ad.msg.coRearm", name(), Integer.valueOf(secs));
            }
            AirDefenseSVA.INSTANCE.launchLog("KP " + name() + " (" + ownerName + "): code-only armed again (the day key / the server's switch back), on in " + secs
                    + " s");
            co = codeOnlyState();
        }
        if (co == coLast) {
            return;
        }
        if (CodeOnlyMath.armedNow(coLast, co)) {
            log("sva.ad.log.coOn");
            for (EntityPlayerMP a : alarmAudience()) {
                AirDefenseSVA.INSTANCE.tell(a, 0xF05050, "sva.ad.msg.coOn", name());
            }
            for (AirDefenseSVA.Contact t : tracks.values()) {
                CodeOnlyMath.hit(t.confirm);
                t.saidCo = t.saidStranger = t.saidEmOver = false;
            }
            for (TileEntityLauncherSVA l : launchers) {
                l.restartEngagements();
            }
            AirDefenseSVA.INSTANCE.launchLog("KP " + name() + " (" + ownerName + "): code-only ON");
        }
        coLast = co;
    }

    /** Item 32: «Огонь по коду: ГОТОВ» or why not, for this post (its launchers' rule that fires most). */
    public int coReadiness() {
        int[] roes = new int[launchers.size()];
        for (int k = 0; k < roes.length; k++) {
            roes[k] = launchers.get(k).roe();
        }
        return CodeOnlyMath.readiness(ConfigSVA.adEnabled, ConfigSVA.adFires(), !ConfigSVA.adRequirePvp || pvpOn(), true, powered, codeOnly(),
                CodeOnlyMath.bestRoe(roes));
    }

    private static boolean pvpOn() {
        net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
        return s != null && s.isPVPEnabled();
    }

    /** World tests: the arming done now (as if codeOnlyArmSeconds went by). */
    public void debugArmNow() {
        info().armAt = worldObj.getTotalWorldTime();
        infoChanged();
    }

    /** World tests: one look at the mode's state (the arming's end told) without waiting for the 10-tick round. */
    public void debugCodeOnlyRound() {
        codeOnlyRound();
    }

    /** The settings into the trust data (a launcher reads them while this post is off or unloaded). */
    private void publishInfo() {
        AirDefenseDataSVA.KpInfo i = info();
        i.owner = ownerName;
        i.name = name();
        i.dim = worldObj.provider.dimensionId;
        i.dayKey = dayKey;
        i.codes = codes.clone();
        i.whiteBoard.clear();
        i.whiteBoard.addAll(whiteBoard);
        i.whiteOwner.clear();
        i.whiteOwner.addAll(whiteOwner);
        i.trusted.clear();
        i.trusted.addAll(trusted);
        i.access.clear();
        i.access.addAll(access);
        infoChanged();
    }

    // ------------------------------------------------------------------ the work

    @Override
    protected void serverTick() {
        if (!zonesPublished) {
            zonesPublished = true;
            publishZones();
        }
        if (!infoPublished || timer % 200 == 0) {
            infoPublished = true;
            ownerSecret();                                          // bug 18: an older save's secret becomes the owner's
            if (legacy) {
                migrate();                                          // audit review: an older save's trusted / white list
            }
            publishInfo();
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
            if (!info().assigned.isEmpty()) {
                info().assigned.clear();                            // 7.3: an order does not wait for the power to come back
                infoChanged();
            }
            // audit review 2026-10-22: the 7600 episodes pause with the post (they kept running: a track back after the
            // outage went straight to the default, the operator had no window)
            for (NoRadioMath.Entry ne : info().noRadio.values()) {
                if (ne.since >= 0) {
                    NoRadioMath.off(ne, now);
                    infoChanged();
                }
            }
            setAlarm(false, false);
            sendViewers();
            return;
        }
        long day = AirDefenseDataSVA.keyPeriod(worldObj);           // code-only wave: the key's clock
        if (day != lastDay) {
            if (lastDay >= 0 && dayCode() >= 0) {
                log("sva.ad.log.newDay", String.valueOf(day), XpdrMath.format(dayCode()));
            }
            lastDay = day;
        }
        codeOnlyRound();                                            // code-only wave: the arming's end (item 16)
        merge(now);
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

    /** The radars and launchers LINKED to this post (audit 2026-10-22, bug 19: each answers to one post only). */
    private void relink() {
        radars = new ArrayList<TileEntityRadarSVA>();
        for (TileEntityRadarSVA r : AirDefenseSVA.INSTANCE.loaded(worldObj, TileEntityRadarSVA.class)) {
            if (r.getOwnerName().equalsIgnoreCase(ownerName) && r.getDistanceFrom(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) <= BallisticsMath.LINK * BallisticsMath.LINK
                    && AirDefenseSVA.INSTANCE.linkedPost(r) == this) {
                radars.add(r);
            }
        }
        launchers = new ArrayList<TileEntityLauncherSVA>();
        for (TileEntityLauncherSVA l : AirDefenseSVA.INSTANCE.loaded(worldObj, TileEntityLauncherSVA.class)) {
            if (l.getOwnerName().equalsIgnoreCase(ownerName) && l.getDistanceFrom(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) <= BallisticsMath.LINK * BallisticsMath.LINK
                    && AirDefenseSVA.INSTANCE.linkedPost(l) == this) {
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
                    t.born = now;
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
                    t.posAt = c.posAt;                              // audit 2026-10-22 (bug 3): the gun leads it by its age
                }
                if (c.altKnown && (k != RadarMath.SURVEY || !t.altKnown)) {
                    t.y = c.y;
                    t.altKnown = true;
                    t.altAt = c.altAt;
                }
                t.kinds |= c.kinds;
                t.v = c.v;
                t.vid = c.vid;
                t.seen = Math.max(t.seen, c.seen);
                t.owner = c.owner;
                t.type = c.type;
                t.manned = c.manned;
                t.vx = c.vx;
                t.vy = c.vy;
                t.vz = c.vz;
                if (k == RadarMath.SURVEY && c.seen >= t.askedAt) {
                    t.replied = c.replied;
                    t.mode = c.mode;
                    t.code = c.code;
                    t.board = c.board;
                    t.ident = c.ident;
                    t.askedAt = c.seen;                             // audit 2026-10-22 (bug 15): interrogated then
                    // code-only wave: the card the code came from (never a code), when it was set, who flies it
                    t.fromCard = c.fromCard;
                    t.cardOwner = c.cardOwner;
                    t.cardGen = c.cardGen;
                    t.cardFirst = c.cardFirst;
                    t.cardUntil = c.cardUntil;
                    t.codeSince = c.codeSince;
                    t.pilot = c.pilot;
                }
            }
        }
        // bug 15: a track no surveillance radar interrogated for a full cycle (none built / powered / seeing it) - the post's
        // own interrogator asks it through the radar that holds it; until the first answer it is UNKNOWN (classify)
        int cycle = RadarMath.INTERVAL[RadarMath.SURVEY] + 20;
        for (AirDefenseSVA.Contact t : tracks.values()) {
            if (t.kinds != 0 && t.v != null && !t.v.isDead && now - (t.askedAt >= 0 ? t.askedAt : t.born) >= cycle) {
                AirDefenseSVA.INSTANCE.interrogate(t, t.v, now);
                t.askedAt = now;
            }
        }
        long today = AirDefenseDataSVA.keyPeriod(worldObj);
        int dc = dayCode(), yc = dc >= 0 ? keyCode(today - 1) : -1;
        // wave A: the aerodromes of every post and tower in this dimension (the friendly airports' codes)
        List<ZoneMath.Zone> apts = new ArrayList<ZoneMath.Zone>();
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(worldObj);
        if (dd != null && ConfigSVA.adAirportFriends) {
            for (ZoneMath.Zone z : dd.all()) {
                if (z.type == ZoneMath.AERODROME && z.dim == worldObj.provider.dimensionId) {
                    apts.add(z);
                }
            }
        }
        AirDefenseDataSVA.KpInfo info = info();
        // code-only wave: the owner's key for this round (null: the usual classification)
        AirDefenseSVA.CodeKey ck = codeOnly() ? AirDefenseSVA.codeKey(worldObj, info) : null;
        for (Iterator<AirDefenseSVA.Contact> it = tracks.values().iterator(); it.hasNext();) {
            AirDefenseSVA.Contact t = it.next();
            if (t.v == null || t.v.isDead || now - t.seen > RadarMath.TRACK_TIMEOUT) {
                it.remove();
                // audit 2026-10-22: its assignment / hold outlive it by KEEP (a chunk reload); its 7600 episode pauses
                NoRadioMath.Entry ne = info.noRadio.get(t.vid);
                if (ne != null && ne.since >= 0) {
                    NoRadioMath.off(ne, now);
                    infoChanged();
                }
                continue;
            }
            classify(t, now, dc, yc, apts, info, ck);
        }
    }

    /**
     * Wave A: an airport handed this track over is one the post listens to (its owner's / a friend's / trusted - audit
     * 2026-10-22: by its ICAO code bound to its owner).
     */
    public boolean friendlyAirport(String aptOwner, String icao) {
        if (aptOwner != null && relation(aptOwner) != 0) {
            return true;
        }
        return AirportCodeMath.trustedTower(trusted, icao, aptOwner);
    }

    /** Wave A: the 7600 decision timer and the friend's time (ticks). */
    static int nrTimer() {
        return Math.max(0, ConfigSVA.adNoRadioDecisionSeconds) * 20;
    }

    static int nrFriendTicks() {
        return Math.max(1, ConfigSVA.adNoRadioFriendMinutes) * 1200;
    }

    /**
     * One track's class and its log events. Code-only wave: with the owner's key `ck` (the mode on) the strict branch -
     * AirDefenseSVA.classifyByCode (the own codes and an airport's code only with the post's options 3а / 3в), the log of
     * «не опознан» / «верный код у чужого борта» / «ПОДБОР» / 7700's grace; null - the usual classification.
     */
    private void classify(final AirDefenseSVA.Contact t, long now, int dc, int yc, List<ZoneMath.Zone> apts, AirDefenseDataSVA.KpInfo info,
            AirDefenseSVA.CodeKey ck) {
        EntityVehicleSVA v = t.v;
        t.rel = relation(t.owner);
        boolean ownFriend = t.rel == 1 || t.rel == 2 && !ConfigSVA.adFireOnFriends;
        String no2 = String.format("%02d", Integer.valueOf(t.no));
        // audit 2026-10-22 (bug 6): the tower's IFF handoff and the zone first - an aerodrome code does not cancel them
        t.iff = IffServerSVA.INSTANCE.stateOf(t.id);
        String handedBy = null;
        for (AirDefenseDataSVA.Handoff h : AirDefenseSVA.INSTANCE.handoffs(worldObj, t.vid, now)) {
            if (friendlyAirport(h.owner, h.icao)) {
                handedBy = h.icao;
                break;
            }
        }
        boolean handed = handedBy != null;
        if (handed && !t.saidIff) {
            log("sva.ad.log.iffHandoff", no2, handedBy);
        }
        t.saidIff = handed;
        ZoneMath.Zone viol = ZoneMath.violated(zones, t.x, t.y, t.z, t.course(), t.altKnown);
        ZoneMath.Zone restr = ZoneMath.inside(zones, ZoneMath.RESTRICTED, t.x, t.y, t.z, t.altKnown);
        boolean ownCode = t.replied && (t.code == dc && dc >= 0 || ownCode(t.code));
        // wave A (A4): a friendly airport's code in / near that airport - audit 2026-10-22 (bugs 6, 7): a tower's only when that
        // tower gave it to THIS aircraft, never over a violation or a handoff
        t.airport = "";
        boolean aptCode = false;
        // audit review 2026-10-22: the post's own aerodrome zone's range is no secret (every client got it with the zones; the
        // default is easy to guess) - an airport's code, not the post's own: no friend over a violation or a handoff
        ZoneMath.Zone own = t.replied && !ownCode ? AirportCodeMath.postRange(zones, t.code, t.x, t.y, t.z, t.altKnown) : null;
        if (own != null && viol == null && !handed) {
            aptCode = true;
            t.airport = own.name;
        }
        if (t.replied && !ownCode && !aptCode && !apts.isEmpty()) {
            ZoneMath.Zone apt = AirportCodeMath.friendOf(apts, t.code, t.x, t.y, t.z, t.altKnown, ownerName, access, trusted, new AirportCodeMath.Issued() {
                @Override
                public boolean to(ZoneMath.Zone a, int code) {
                    return AtcServerSVA.INSTANCE.issued(AirportCodeMath.towerKey(a), code, t.id, t.vid);
                }
            });
            if (apt != null && viol == null && !handed) {
                aptCode = true;
                t.airport = apt.name;
            }
        }
        boolean white = t.replied && XpdrMath.givesBoard(t.mode) && whitelisted(t.board, t.owner);
        boolean granted = info.granted(t.vid, now);
        // wave A (A5): 7600 - the operator's decision (pending / «проверить»: no fire; «свой»: a friend; «враждебный» or none in
        // time: as usual). Audit 2026-10-22 (bug 8): the vehicle's episodes together - a code changed away and back pauses
        // the timer, it does not start a new one
        boolean nr = t.replied && t.code == XpdrMath.NO_RADIO;
        NoRadioMath.Entry ne = info.noRadio.get(t.vid);
        if (nr && ne == null) {
            ne = new NoRadioMath.Entry(now);
            info.noRadio.put(t.vid, ne);
            infoChanged();
            log("sva.ad.log.noRadio", no2, String.valueOf(nrTimer() / 20));
            worldObj.playSoundEffect(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, "note.pling", 1F, 0.8F);
        } else if (nr && (ne.since < 0 || now - ne.lastSeen > NoRadioMath.GAP)) {
            // audit review 2026-10-22: back after a pause - or after the post's power / chunk was away (the episode ended at
            // its last sighting, the time unseen does not count)
            NoRadioMath.seen(ne, now);
            infoChanged();
            log("sva.ad.log.noRadioAgain", no2, String.valueOf(NoRadioMath.secondsLeft(ne, now, nrTimer())));
        } else if (nr) {
            ne.lastSeen = now;
        } else if (ne != null && ne.since >= 0) {
            NoRadioMath.changed(ne, now);                           // audit review: «проверить» goes with the episode
            infoChanged();
            log("sva.ad.log.noRadioOff", no2);
        }
        int nst = nr ? NoRadioMath.state(ne, now, nrTimer()) : NoRadioMath.S_DEFAULT;
        if (nr && nst == NoRadioMath.S_DEFAULT && !ne.defaultLogged) {
            ne.defaultLogged = true;
            log("sva.ad.log.noRadioDefault", no2);
        }
        t.nr = nr ? nst : -1;
        t.nrLeft = nr ? NoRadioMath.secondsLeft(ne, now, nrTimer()) : 0;
        boolean grace = nr && NoRadioMath.holdFire(nst);
        granted |= nr && NoRadioMath.friend(nst);
        if (ck != null) {
            // code-only wave: the key's code alone (no ownership, no white list, no tower trust; own / airport codes as options)
            int r = AirDefenseSVA.INSTANCE.classifyByCode(worldObj, ck, t, t.askedAt >= 0, t.askedAt, granted, grace, ownCode(t.code), aptCode,
                    viol != null || handed, now);
            codeOnlyLog(t, r, no2, now);
        } else {
            t.codeOnly = false;
            t.idr = t.idMiss = RoeMath.ID_NONE;
            t.em = -1;
            t.cls = RoeMath.classify(ownFriend, t.replied, t.code, ownCode, aptCode, white, granted, grace, viol != null || handed);
            t.cls = RoeMath.unknownUntilAsked(t.cls, t.askedAt >= 0);         // bug 15: never interrogated - no fire
        }
        // «только вести» (a real hold-fire for this post's launchers), renewed while the track lasts
        t.held = info.held(t.vid, now);
        if (t.held) {
            info.holds.put(t.vid, Long.valueOf(now + KEEP));
            infoChanged();
        }
        String who = t.board > 0 ? "B-" + t.board : String.format("%02d", Integer.valueOf(t.no));
        // events
        String zn = viol == null ? "" : viol.name;
        if (!zn.equals(t.zone) && t.cls != RoeMath.UNKNOWN) {
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
            if (t.codeOnly && t.emLeft >= 0) {
                log("sva.ad.log.emGrace", who, String.valueOf(t.emLeft));   // code-only wave (item 7): a grace, not for ever
            } else {
                log("sva.ad.log.emergency", who);
            }
            worldObj.playSoundEffect(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, "note.pling", 1F, 0.6F);
        }
        t.said7700 = em;
        // audit 2026-10-22: 7500 (unlawful interference) - the log, the alarm and its message to the owner's list
        boolean hj = t.replied && t.code == XpdrMath.HIJACK;
        if (hj && !t.said7500) {
            // audit review 2026-10-22: the alarm, the log line, the sound and the message to the whole list once a vehicle
            // in XpdrMath.HIJACK_REPEAT - 7500 / 7000 toggled on the panel sent them every survey round, without end
            Long last = hijackSaid.get(t.vid);
            if (XpdrMath.hijackAlarm(last == null ? -1 : last.longValue(), now)) {
                if (hijackSaid.size() > 256) {
                    hijackSaid.clear();
                }
                hijackSaid.put(t.vid, Long.valueOf(now));
                setAlarm(true, false);
                log("sva.ad.log.hijack", who);
                worldObj.playSoundEffect(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, "note.bassattack", 2F, 0.7F);
                alarmMessage("sva.ad.msg.hijack", who);
            }
        }
        if (hj) {
            lastViolator = now;                                     // the alarm holds while it squawks 7500
        }
        t.said7500 = hj;
        boolean old = t.replied && yc >= 0 && t.code == yc && t.code != dc;
        if (old && !t.saidOld) {
            log("sva.ad.log.oldCode", String.format("%02d", Integer.valueOf(t.no)), XpdrMath.format(t.code));
        }
        t.saidOld = old;
        AirDefenseDataSVA.Asg a = info.assigned.get(t.vid);
        t.assigned = "";
        if (a != null) {
            a.until = Math.max(a.until, now + KEEP);                // lasts while the track does (+ KEEP)
            for (TileEntityLauncherSVA l : launchers) {
                if (l.key().equals(a.launcher)) {
                    t.assigned = String.valueOf(launcherNo(l));
                }
            }
        }
        Object[] en = engaged.get(Integer.valueOf(t.id));
        t.stage = en == null ? RoeMath.ST_NONE : ((Integer) en[1]).intValue();
        t.toFire = en == null ? 0 : ((Integer) en[2]).intValue();
        t.engagedBy = en == null ? "" : (String) en[0];
    }

    /**
     * Code-only wave: the strict verdict's log lines - the «ПОДБОР» block began (item 11); a target confirmed: «не опознан» with
     * the pilot's reason, for a machine of the owner or his list «... - борт ВЛАДЕЛЬЦА» and the list told in the chat (item
     * 33: information only); the key's card on a stranger's aircraft - «верный код у чужого борта» (item 30); 7700's grace used
     * up (item 7). Each once a track until it changes.
     */
    private void codeOnlyLog(AirDefenseSVA.Contact t, int r, String no2, long now) {
        if ((r & AirDefenseSVA.PROBE_BEGAN) != 0) {
            log("sva.ad.log.coProbe", no2, String.valueOf(Math.max(1, ConfigSVA.adCodeOnlyProbeMinutes)));
            worldObj.playSoundEffect(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, "note.bassattack", 1.5F, 0.9F);
            AirDefenseSVA.INSTANCE.launchLog("KP " + name() + ": track " + no2 + " (" + t.owner + ", pilot " + t.pilot + ") - GUESSING block");
        }
        boolean target = RoeMath.hostile(t.cls);
        if (target && !t.saidCo) {
            String why = CodeOnlyMath.PILOT_KEYS[CodeOnlyMath.pilotReason(t.idMiss)];
            if (CodeOnlyMath.ownWithoutCode(t.rel, t.cls)) {
                log("sva.ad.log.coMissOwn." + why, no2, t.owner);
                for (EntityPlayerMP a : alarmAudience()) {
                    AirDefenseSVA.INSTANCE.tell(a, 0xF05050, "sva.ad.msg.coOwnNoCode", name(), no2, t.owner);
                }
            } else {
                log("sva.ad.log.coMiss." + why, no2);
            }
        }
        t.saidCo = target;
        boolean piloted = t.pilot.length() > 0;
        boolean stranger = CodeOnlyMath.strangerWithKey(t.idr, t.fromCard, piloted, piloted ? relation(t.pilot) : 0, t.rel);
        if (stranger && !t.saidStranger) {
            log("sva.ad.log.coStranger", no2, piloted ? t.pilot : t.owner);
            AirDefenseSVA.INSTANCE.launchLog("KP " + name() + ": track " + no2 + " - the key's code from a card, flown by a stranger (" + (piloted ? t.pilot : t.owner)
                    + ")");
        }
        t.saidStranger = stranger;
        boolean over = t.em == NoRadioMath.S_DEFAULT;
        if (over && !t.saidEmOver) {
            log("sva.ad.log.emOver", no2);
        }
        t.saidEmOver = over;
    }

    /** One of the own codes - never a reserved one (code-only wave, item 35: an older save's 7000 no longer makes everyone a friend). */
    public boolean ownCode(int code) {
        if (!XpdrMath.ownCodeAllowed(code)) {
            return false;
        }
        for (int c : codes) {
            if (c == code) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mode S white list: the board and its owner (audit 2026-10-22: an entry without an owner matches nobody - board numbers
     * repeat; the settings bind a bare number to the owner of the aircraft the post sees with it).
     */
    public boolean whitelisted(int board, String owner) {
        for (int i = 0; i < whiteBoard.size(); i++) {
            if (AirportCodeMath.whiteMatch(whiteBoard.get(i).intValue(), whiteOwner.get(i), board, owner)) {
                return true;                                        // audit review: an older save's entry - any owner till saved
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
        AirDefenseDataSVA.KpInfo info = info();
        for (AirDefenseSVA.Contact t : tracks.values()) {
            if (info.assigned.containsKey(t.vid) && !out.contains(Integer.valueOf(t.id))) {
                out.add(Integer.valueOf(t.id));
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
        AirDefenseSVA.Contact t = tracks.get(Integer.valueOf(id));
        AirDefenseDataSVA.Asg a = t == null ? null : info().assigned.get(t.vid);
        return a != null && a.launcher.equals(l.key());
    }

    /** Audit 2026-10-22: who assigned this track to this launcher ("" not assigned to it; "-" an order without a player). */
    public String assignedBy(int id, TileEntityLauncherSVA l) {
        AirDefenseSVA.Contact t = tracks.get(Integer.valueOf(id));
        AirDefenseDataSVA.Asg a = t == null ? null : info().assigned.get(t.vid);
        return a == null || !a.launcher.equals(l.key()) ? "" : a.by.length() == 0 ? "-" : a.by;
    }

    /** Audit 2026-10-22: «только вести» on this track - this post's launchers hold their fire (no warnings either). */
    public boolean heldFire(int id) {
        AirDefenseSVA.Contact t = tracks.get(Integer.valueOf(id));
        return t != null && t.held;
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

    /** The model (multiblock wave): power; lock - tracks on the picture; alarm - the alarm (the beacon, the blinking lamp). */
    @Override
    protected int stateBits() {
        int s = super.stateBits();
        if (powered && !tracks().isEmpty()) {
            s |= ST_LOCK;
        }
        return alarm ? s | ST_ALARM : s;
    }

    @Override
    protected void writeSync(NBTTagCompound t) {
        t.setBoolean("A", alarm);
    }

    @Override
    protected void readSync(NBTTagCompound t) {
        boolean was = clientAlarm;
        clientAlarm = t.getBoolean("A");
        if (was != clientAlarm && worldObj != null) {
            worldObj.markBlockRangeForRenderUpdate(xCoord, yCoord, zCoord, xCoord, yCoord, zCoord);
        }
    }

    private void alarmMessage(String zone) {
        alarmMessage("sva.ad.msg.alarm", zone);
    }

    /**
     * The alarm's chat line to the post's owner and everyone on its access list - audit 2026-10-22 (7.3): wherever they are
     * (every dimension; it went only to the post's own), as sva.ad.kp.alarm.tip promises.
     */
    private void alarmMessage(String key, String what) {
        for (EntityPlayerMP p : alarmAudience()) {
            AirDefenseSVA.INSTANCE.tell(p, 0xF05050, key, name(), what);
        }
    }

    /** Who gets the alarm's chat line: the owner and the access list, online in any dimension. */
    public List<EntityPlayerMP> alarmAudience() {
        List<EntityPlayerMP> out = new ArrayList<EntityPlayerMP>();
        net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
        java.util.List<?> all = s != null && s.getConfigurationManager() != null ? s.getConfigurationManager().playerEntityList : worldObj.playerEntities;
        for (Object o : all) {
            if (o instanceof EntityPlayerMP && relation(((EntityPlayerMP) o).getCommandSenderName()) != 0) {
                out.add((EntityPlayerMP) o);
            }
        }
        return out;
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
                String by = p == null ? "-" : p.getCommandSenderName();
                info().assigned.put(t.vid, new AirDefenseDataSVA.Asg(chosen.key(), now + KEEP, by));
                info().holds.remove(t.vid);                         // an order to fire ends «только вести»
                infoChanged();
                log("sva.ad.log.assigned", String.format("%02d", Integer.valueOf(t.no)), String.valueOf(launcherNo(chosen)));
                // audit 2026-10-22: who gave the order goes to the launch log too
                AirDefenseSVA.INSTANCE.launchLog(String.format("KP %s: track %02d (%s) assigned to L-%d by %s", name(), Integer.valueOf(t.no), t.owner,
                        Integer.valueOf(launcherNo(chosen)), by));
            }
        } else if ("track".equals(op) && t != null) {
            // audit 2026-10-22: «только вести» - a real hold-fire on this post's launchers (whatever their rule, «Авто» too)
            info().assigned.remove(t.vid);
            info().holds.put(t.vid, Long.valueOf(now + KEEP));
            infoChanged();
            t.held = true;
            log("sva.ad.log.trackOnly", String.format("%02d", Integer.valueOf(t.no)));
        } else if ("grant".equals(op) && t != null) {
            info().grants.put(t.vid, Long.valueOf(now + RoeMath.GRANT_TICKS));
            info().assigned.remove(t.vid);
            infoChanged();
            AirDefenseDataSVA gd = AirDefenseDataSVA.get(worldObj);
            if (gd != null) {
                gd.probeUnblock(ownerName, t.vid, t.pilot);         // code-only wave (item 11): «Свой» lifts the «ПОДБОР» block too
            }
            log("sva.ad.log.grant", String.format("%02d", Integer.valueOf(t.no)));
        } else if ("unlock".equals(op) && t != null) {
            // code-only wave (item 11): the operator lifts the «ПОДБОР» block (the aircraft's and its pilot's)
            AirDefenseDataSVA gd = AirDefenseDataSVA.get(worldObj);
            if (gd != null) {
                gd.probeUnblock(ownerName, t.vid, t.pilot);
            }
            log("sva.ad.log.coUnlock", String.format("%02d", Integer.valueOf(t.no)), p == null ? "-" : p.getCommandSenderName());
        } else if (("emFriend".equals(op) || "emCheck".equals(op) || "emHostile".equals(op)) && t != null) {
            // code-only wave (item 7): the operator's decision on a 7700 - kept with its grace per owner (all his posts)
            AirDefenseDataSVA gd = AirDefenseDataSVA.get(worldObj);
            NoRadioMath.Entry e = gd == null ? null : gd.emergencyEntry(ownerName, t.vid);
            if (e != null) {
                int dec = "emFriend".equals(op) ? NoRadioMath.D_FRIEND : "emCheck".equals(op) ? NoRadioMath.D_CHECK : NoRadioMath.D_HOSTILE;
                NoRadioMath.decide(e, dec, now, nrFriendTicks());
                if (dec == NoRadioMath.D_FRIEND) {
                    gd.probeUnblock(ownerName, t.vid, t.pilot);     // review 2026-10-27 (batch 3, item 11): «Свой» lifts «ПОДБОР» too
                }
                if (dec != NoRadioMath.D_HOSTILE) {
                    info().assigned.remove(t.vid);
                    infoChanged();
                }
                gd.markDirty();
                String who = p == null ? "-" : p.getCommandSenderName();
                String tn = String.format("%02d", Integer.valueOf(t.no));
                if (dec == NoRadioMath.D_FRIEND) {
                    log("sva.ad.log.emFriend", tn, String.valueOf(ConfigSVA.adNoRadioFriendMinutes), who);
                } else {
                    log(dec == NoRadioMath.D_CHECK ? "sva.ad.log.emCheck" : "sva.ad.log.emHostile", tn, who);
                }
                AirDefenseSVA.INSTANCE.launchLog("KP " + name() + ": 7700 track " + t.no + " (" + t.owner + ") - "
                        + NoRadioMath.STATE_KEYS[dec == NoRadioMath.D_FRIEND ? NoRadioMath.S_FRIEND : dec == NoRadioMath.D_CHECK ? NoRadioMath.S_CHECK
                                : NoRadioMath.S_HOSTILE] + " by " + who);
            }
        } else if ("codeOnly".equals(op) || "codeOpts".equals(op) || "rekey".equals(op) || "coAllRoe".equals(op)) {
            codeOnlyCommand(p, op, d);
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
        } else if ("nrFriend".equals(op) || "nrCheck".equals(op) || "nrHostile".equals(op)) {
            // wave A (A5): the operator's decision on a 7600 track (audit 2026-10-22, bug 8: «враждебный» too)
            NoRadioMath.Entry ne = t == null ? null : info().noRadio.get(t.vid);
            if (t != null && ne != null) {
                int dec = "nrFriend".equals(op) ? NoRadioMath.D_FRIEND : "nrCheck".equals(op) ? NoRadioMath.D_CHECK : NoRadioMath.D_HOSTILE;
                NoRadioMath.decide(ne, dec, now, nrFriendTicks());
                if (dec != NoRadioMath.D_HOSTILE) {
                    info().assigned.remove(t.vid);
                }
                infoChanged();
                log(dec == NoRadioMath.D_FRIEND ? "sva.ad.log.noRadioFriend" : dec == NoRadioMath.D_CHECK ? "sva.ad.log.noRadioCheck"
                        : "sva.ad.log.noRadioHostile", String.format("%02d", Integer.valueOf(t.no)), String.valueOf(ConfigSVA.adNoRadioFriendMinutes));
                AirDefenseSVA.INSTANCE.launchLog("KP " + name() + ": 7600 track " + t.no + " (" + t.owner + ") - "
                        + NoRadioMath.STATE_KEYS[dec == NoRadioMath.D_FRIEND ? NoRadioMath.S_FRIEND : dec == NoRadioMath.D_CHECK ? NoRadioMath.S_CHECK
                                : NoRadioMath.S_HOSTILE]);
            }
        } else if ("cov".equals(op)) {
            // wave A (A9): the coverage maps of the linked radars at a height, to this player
            int agl = d.getInteger("A");
            for (TileEntityRadarSVA r : radars) {
                if (!r.isInvalid() && r.isPowered()) {
                    com.sva.airdefense.CoverageSVA.INSTANCE.request(p, r, agl);
                }
            }
            return;
        } else if ("print".equals(op)) {
            printFromPaper(p);
        } else if ("zone".equals(op)) {
            ZoneMath.Zone z = new ZoneMath.Zone();
            z.read(d.getCompoundTag("Z"));
            int i = d.getInteger("Idx");
            boolean edit = i >= 0 && i < zones.size();
            if (z.name.length() == 0) {
                z.name = "Z" + (zones.size() + 1);
            }
            // audit 2026-10-22 (bug 1): not reaching further than the server allows from this post (a modified client's
            // box over the whole world, or a zone over someone's base far away), not more than the owner may hold
            if (!ZoneMath.withinReach(z, xCoord, zCoord, ConfigSVA.adZoneMaxDistance)) {
                AirDefenseSVA.INSTANCE.tell(p, 0xF0B040, "sva.ad.msg.zoneFar", Integer.valueOf(ConfigSVA.adZoneMaxDistance));
                return;
            }
            // embassy airfield (plan §3.6): no forbidden zone over an embassy's neutral sky (the launchers hold their fire there)
            if (EmbassyAdSVA.underNeutralSky(worldObj, z)) {
                AirDefenseSVA.INSTANCE.tell(p, 0xF0B040, "sva.emb.zoneNeutral");
                return;
            }
            if (!edit && !zoneRoom()) {
                AirDefenseSVA.INSTANCE.tell(p, 0xF0B040, "sva.ad.msg.zoneLimit", Integer.valueOf(ZoneMath.MAX_ZONES),
                        Integer.valueOf(ConfigSVA.adMaxZonesPerPlayer));
                return;
            }
            if (edit) {
                zones.set(i, z);
            } else {
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
            // audit 2026-10-22 (bug 18): the owner's one secret - every post of his changes its day code with it
            String s = Text.clean(d.getString("S"), 32);
            secret = s.length() == 0 ? randomSecret() : s;
            AirDefenseDataSVA dd = AirDefenseDataSVA.get(worldObj);
            if (dd != null) {
                dd.setSecret(ownerName, secret);
            }
            log("sva.ad.log.secret", XpdrMath.format(dayCode() < 0 ? 0 : dayCode()));
            markDirty();
        } else if ("open".equals(op)) {
            addViewer(p);
        }
        // audit 2026-10-22: no picture from here - every command sent it to every viewer (24 a tick from one player);
        // the screens see the change with the regular send (at most 10 ticks)
    }

    /**
     * Code-only wave: the owner's commands (an op counts as the owner - item 34) - «codeOnly» (On; switching it on needs
     * «Sure» from the confirmation window: two steps), «codeOpts» (Opts - CodeOnlyMath.OPT_*, Periods - the cards' term),
     * «rekey» («Сменить ключ», with «Sure»: the generation +1, every card printed before is void, the list told to print new
     * ones) and «coAllRoe» («Все ПУ: по всем без кода», item 27). Anyone else is refused with a line in the chat.
     */
    private void codeOnlyCommand(EntityPlayerMP p, String op, NBTTagCompound d) {
        if (!isOwner(p)) {
            if (p != null) {
                AirDefenseSVA.INSTANCE.tell(p, 0xF0B040, "sva.ad.msg.coOwnerOnly");
            }
            return;
        }
        String who = p.getCommandSenderName();
        if ("codeOnly".equals(op)) {
            boolean on = d.getBoolean("On");
            if (on && !d.getBoolean("Sure")) {
                AirDefenseSVA.INSTANCE.tell(p, 0xF0B040, "sva.ad.msg.coConfirm");
                return;
            }
            setCodeOnly(p, on);
        } else if ("codeOpts".equals(op)) {
            AirDefenseDataSVA.KpInfo i = info();
            if (d.hasKey("Opts")) {
                i.coOpts = CodeOnlyMath.opts(d.getInteger("Opts"));
            }
            if (d.hasKey("Periods")) {
                i.cardPeriods = CodeOnlyMath.cardPeriods(d.getInteger("Periods"), ConfigSVA.adCodeCardMaxPeriods);
            }
            infoChanged();
            log("sva.ad.log.coOpts", who);
        } else if ("rekey".equals(op)) {
            AirDefenseDataSVA dd = AirDefenseDataSVA.get(worldObj);
            if (!d.getBoolean("Sure") || dd == null) {
                AirDefenseSVA.INSTANCE.tell(p, 0xF0B040, "sva.ad.msg.coConfirm");
                return;
            }
            int gen = dd.rekey(ownerName);
            log("sva.ad.log.rekey", String.valueOf(gen), who);
            for (EntityPlayerMP a : alarmAudience()) {
                AirDefenseSVA.INSTANCE.tell(a, 0xF05050, "sva.ad.msg.rekey", name(), Integer.valueOf(gen));
            }
            AirDefenseSVA.INSTANCE.launchLog("KP " + name() + " (" + ownerName + "): the key changed, generation " + gen + ", by " + who);
        } else {
            int n = 0;
            for (TileEntityLauncherSVA l : launchers) {
                if (!l.isInvalid()) {
                    l.setRoeByPost(RoeMath.FOREIGN_ALL);
                    n++;
                }
            }
            log("sva.ad.log.coAllRoe", String.valueOf(n), who);
        }
    }

    /**
     * Bug 1: room for one more zone - under ZoneMath.MAX_ZONES on this post and, with the zones of the owner's other posts
     * (AirDefenseDataSVA), under airdefense.maxZonesPerPlayer (0 - no limit).
     */
    public boolean zoneRoom() {
        if (zones.size() >= ZoneMath.MAX_ZONES) {
            return false;
        }
        int max = ConfigSVA.adMaxZonesPerPlayer;
        AirDefenseDataSVA d = AirDefenseDataSVA.get(worldObj);
        return max <= 0 || d == null || d.countOwnerZones(ownerName, key()) + zones.size() < max;
    }

    private void settings(EntityPlayerMP p, NBTTagCompound d) {
        name = Text.clean(d.getString("Name"), 24);
        if (isOwner(p)) {
            boolean keyWas = dayKey;
            dayKey = d.getBoolean("DayKey");                        // audit 2026-10-22 (7.3): the access list may not switch it off
            AirDefenseDataSVA.KpInfo ci = info();
            if (CodeOnlyMath.disarmOnKey(ci.codeOnly, keyWas, dayKey)) {
                ci.armAt = -1;                                      // review 2026-10-27: the key on again arms it anew (codeOnlyRound)
                infoChanged();
            }
            AirDefenseDataSVA zd = keyWas != dayKey ? AirDefenseDataSVA.get(worldObj) : null;
            if (zd != null) {
                ci.dayKey = dayKey;
                zd.touchZones();                                    // review batch 3: the zones' «ПО КОДУ» mark follows the day key
            }
        }
        int[] c = d.getIntArray("Codes");
        for (int i = 0; i < MAX_CODES; i++) {
            // code-only wave (item 35): 0000 / 7000 / 7500 / 7600 / 7700 (and 77xx) are no own codes - dropped
            codes[i] = i < c.length && XpdrMath.ownCodeAllowed(c[i]) ? c[i] : -1;
        }
        int[] wb = d.getIntArray("WB");
        NBTTagList wo = d.getTagList("WO", 8);
        // audit review 2026-10-22: a bare number is bound only when it is newly typed (and the chat says to whom) - one kept
        // bare from an earlier save is not bound by a later save of anything else (to a stranger seen with the number by then)
        List<String> bareBefore = new ArrayList<String>();
        for (int i = 0; i < whiteBoard.size(); i++) {
            String o = whiteOwner.get(i);
            if (o.length() == 0 || AirportCodeMath.ANY_OWNER.equals(o)) {
                bareBefore.add(String.valueOf(whiteBoard.get(i)));
            }
        }
        whiteBoard.clear();
        whiteOwner.clear();
        StringBuilder unbound = new StringBuilder(), bound = new StringBuilder();
        for (int i = 0; i < wb.length && i < MAX_WHITE; i++) {
            if (wb[i] > 0 && wb[i] < 100) {
                String o = i < wo.tagCount() ? Text.clean(wo.getStringTagAt(i), 32) : "";
                if (AirportCodeMath.ANY_OWNER.equals(o)) {
                    o = "";                                         // an older save's «any owner» is not typed back in
                }
                if (o.length() == 0 && AirportCodeMath.mayBind(String.valueOf(wb[i]), bareBefore)) {
                    o = boardOwner(wb[i]);                          // audit 2026-10-22: a bare number - the owner seen with it
                    if (o.length() > 0) {
                        bound.append(bound.length() > 0 ? ", " : "").append(String.format("%02d", Integer.valueOf(wb[i]))).append(':').append(o);
                    }
                }
                if (o.length() == 0) {
                    unbound.append(unbound.length() > 0 ? ", " : "").append(wb[i]);
                }
                whiteBoard.add(Integer.valueOf(wb[i]));
                whiteOwner.add(o);
            }
        }
        if (bound.length() > 0 && p != null) {
            AirDefenseSVA.INSTANCE.tell(p, 0xF0B040, "sva.ad.msg.whiteBound", bound.toString());
        }
        if (unbound.length() > 0 && p != null) {
            AirDefenseSVA.INSTANCE.tell(p, 0xF0B040, "sva.ad.msg.whiteUnbound", unbound.toString());
        }
        if (d.hasKey("Trust")) {
            StringBuilder tb = new StringBuilder();
            List<String> l = bindTrusted(AirportCodeMath.parseTrusted(Text.clean(d.getString("Trust"), 200)), new ArrayList<String>(trusted), tb);
            trusted.clear();
            trusted.addAll(l);
            StringBuilder unknown = new StringBuilder();
            for (String e : l) {
                if (AirportCodeMath.ownerOf(e).length() == 0) {
                    unknown.append(unknown.length() > 0 ? ", " : "").append(e);
                }
            }
            if (tb.length() > 0 && p != null) {
                AirDefenseSVA.INSTANCE.tell(p, 0xF0B040, "sva.ad.msg.trustBound", tb.toString());
            }
            if (unknown.length() > 0 && p != null) {
                AirDefenseSVA.INSTANCE.tell(p, 0xF0B040, "sva.ad.msg.trustUnknown", unknown.toString());
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
        publishInfo();
        markDirty();
    }

    /** The owner of the aircraft this post sees answering Mode S with that board ("" none, or two owners). */
    private String boardOwner(int board) {
        String o = "";
        for (AirDefenseSVA.Contact t : tracks.values()) {
            if (t.replied && XpdrMath.givesBoard(t.mode) && t.board == board && t.owner.length() > 0) {
                if (o.length() > 0 && !o.equalsIgnoreCase(t.owner)) {
                    return "";
                }
                o = t.owner;
            }
        }
        return o;
    }

    /**
     * Audit 2026-10-22 (bug 7): the trusted ICAO codes bound to their towers' owners now (unknown ones stay unbound - trust
     * nobody). Audit review: only the entries AirportCodeMath.mayBind lets (newly typed - `before` the list saved so far,
     * null: all); each one bound goes into `told` («UBTW:Bob») for the chat.
     */
    private List<String> bindTrusted(List<String> l, List<String> before, StringBuilder told) {
        AirportDataSVA ad = AirportDataSVA.get(worldObj);
        List<String> out = new ArrayList<String>();
        for (String e : l) {
            String owner = "";
            if (ad != null && AirportCodeMath.mayBind(e, before)) {
                for (AirportDataSVA.Tower tw : ad.towers()) {
                    if (tw.icao.equalsIgnoreCase(AirportCodeMath.icaoOf(e))) {
                        owner = tw.owner;
                    }
                }
            }
            String b = AirportCodeMath.bind(e, owner);
            if (!b.equals(e) && told != null) {
                told.append(told.length() > 0 ? ", " : "").append(b);
            }
            out.add(b);
        }
        return out;
    }

    /**
     * Audit review 2026-10-22: a post saved before the owner binding - its bare trusted ICAO codes (they trusted any zone of
     * that name) are bound to their towers' owners once, at its first tick; its white list's bare numbers (any owner, as
     * 0.1.1 did) stay so (AirportCodeMath.ANY_OWNER) until the settings are saved. Both go into the post's log - before,
     * they silently stopped protecting anyone after the update.
     */
    private void migrate() {
        legacy = false;
        boolean bare = false;
        for (String e : trusted) {
            bare |= AirportCodeMath.ownerOf(e).length() == 0;
        }
        if (bare) {
            List<String> l = bindTrusted(new ArrayList<String>(trusted), null, null);
            trusted.clear();
            trusted.addAll(l);
            log("sva.ad.log.legacyTrust", AirportCodeMath.joinTrusted(trusted));
        }
        StringBuilder any = new StringBuilder();
        for (int i = 0; i < whiteBoard.size(); i++) {
            if (AirportCodeMath.ANY_OWNER.equals(whiteOwner.get(i))) {
                any.append(any.length() > 0 ? ", " : "").append(String.format("%02d", whiteBoard.get(i)));
            }
        }
        if (any.length() > 0) {
            log("sva.ad.log.legacyWhite", any.toString());
        }
        if (bare || any.length() > 0) {
            System.out.println("[SVA-AD] command post " + key() + " (" + ownerName + "): an older save's trust - trusted " + trusted
                    + ", white list for any owner: " + any);
            markDirty();
        }
    }

    /** World tests (audit review 2026-10-22): an older save's post as it is updated at its first tick. */
    public void debugMigrate() {
        if (legacy) {
            migrate();
        }
    }

    /** World tests: the trusted list as saved. */
    public List<String> debugTrusted() {
        return new ArrayList<String>(trusted);
    }

    /**
     * Right-click with code cards: the whole stack printed with the owner's key (the day key on) - code-only wave (item 17):
     * whose key, its generation, this key period and the post's «Срок карты» (1 / 3 / 7 periods, never over the server's
     * codeCardMaxPeriods); no code on it. The log says who printed how many. @return printed
     */
    public boolean printCard(EntityPlayer p, ItemStack card) {
        if (dayCode() < 0 || !powered || card == null) {
            return false;
        }
        printKey(card);
        log("sva.ad.log.printedN", p == null ? "?" : p.getCommandSenderName(), String.valueOf(card.stackSize));
        return true;
    }

    /** The owner's key on a card (a stack: all of them) - this period, the post's periods. */
    private void printKey(ItemStack card) {
        AirDefenseDataSVA d = AirDefenseDataSVA.get(worldObj);
        ItemCodeCardSVA.print(card, name(), ownerName, d == null ? 1 : d.gen(ownerName), AirDefenseDataSVA.keyPeriod(worldObj), cardPeriods());
    }

    /** The periods this post's cards are good for (its «Срок карты» within the server's codeCardMaxPeriods). */
    public int cardPeriods() {
        return CodeOnlyMath.cardPeriods(info().cardPeriods, ConfigSVA.adCodeCardMaxPeriods);
    }

    /** World tests: the owner's key on a card whatever the power and the day key. */
    public void printCardForTest(ItemStack card) {
        ownerSecret();
        printKey(card);
    }

    /** «Карты кодов: печать»: a sheet of paper from the operator's inventory becomes a card of the owner's key. */
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
        printKey(s);
        if (!p.inventory.addItemStackToInventory(s)) {
            p.dropPlayerItemWithRandomChoice(s, false);
        }
        p.inventoryContainer.detectAndSendChanges();
        log("sva.ad.log.printedN", p.getCommandSenderName(), "1");
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
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(worldObj);
        if (dd != null) {
            dd.setSecret(ownerName, secret);                        // the owner's one secret (bug 18)
        }
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
        publishInfo();
    }

    /** World tests: the persistent id of the vehicle with this entity id ("" none loaded). */
    private String vidOf(int id) {
        AirDefenseSVA.Contact t = tracks.get(Integer.valueOf(id));
        if (t != null && t.vid.length() > 0) {
            return t.vid;
        }
        net.minecraft.entity.Entity e = worldObj.getEntityByID(id);
        return e instanceof EntityVehicleSVA ? AirDefenseSVA.vehKey((EntityVehicleSVA) e) : "";
    }

    /** World tests: «свой на 10 минут» / an assignment / «Авто» without the screen. */
    public void debugGrant(int id) {
        info().grants.put(vidOf(id), Long.valueOf(worldObj.getTotalWorldTime() + RoeMath.GRANT_TICKS));
        infoChanged();
    }

    /** World tests (audit 2026-10-22): «только вести» on a vehicle without a track (a post that is off). */
    public void debugHold(int id) {
        info().holds.put(vidOf(id), Long.valueOf(worldObj.getTotalWorldTime() + KEEP));
        infoChanged();
    }

    public void debugAssign(int id, TileEntityLauncherSVA l) {
        info().assigned.put(vidOf(id), new AirDefenseDataSVA.Asg(l.key(), worldObj.getTotalWorldTime() + KEEP));
        infoChanged();
    }

    /** World tests (wave A): the trusted airports (bound to their towers' owners as the settings do); a 7600 track's entry. */
    public void debugTrust(List<String> names) {
        List<String> l = bindTrusted(AirportCodeMath.parseTrusted(AirportCodeMath.joinTrusted(names)), null, null);
        trusted.clear();
        trusted.addAll(l);
        publishInfo();
    }

    public NoRadioMath.Entry noRadioEntry(int id) {
        return info().noRadio.get(vidOf(id));
    }

    public void debugNoRadio(int id, boolean friend) {
        debugNoRadio(id, friend ? NoRadioMath.D_FRIEND : NoRadioMath.D_CHECK);
    }

    /** World tests (audit 2026-10-22): any 7600 decision (NoRadioMath.D_*) without the screen. */
    public void debugNoRadio(int id, int decision) {
        NoRadioMath.Entry ne = info().noRadio.get(vidOf(id));
        if (ne != null) {
            NoRadioMath.decide(ne, decision, worldObj.getTotalWorldTime(), nrFriendTicks());
            infoChanged();
        }
    }

    // ------------------------------------------------------------------ the screen

    public void addViewer(EntityPlayer p) {
        viewers.put(p, Long.valueOf(worldObj.getTotalWorldTime()));
    }

    public void removeViewer(EntityPlayer p) {
        viewers.remove(p);
    }

    /** World tests: the player's screen still gets the picture. */
    public boolean hasViewer(EntityPlayer p) {
        return viewers.containsKey(p);
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
            if (!mayUse(p)) {
                // audit 2026-10-22: taken off the access list - no more pictures (the day code in them); his screen closes
                it.remove();
                NBTTagCompound shut = new NBTTagCompound();
                shut.setIntArray("Pos", new int[] {xCoord, yCoord, zCoord});
                shut.setBoolean("Shut", true);
                NetADSVA.send((EntityPlayerMP) p, NetADSVA.D_KP, shut);
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
        long day = AirDefenseDataSVA.keyPeriod(worldObj);
        t.setLong("Day", day);
        t.setString("Time", clock(worldObj.getWorldTime()));
        t.setInteger("DayCode", dayCode());
        // code-only wave: «смена ключа через N мин» on the key's clock, the key's generation, the cards' periods
        t.setInteger("KeyLeft", (int) AirDefenseDataSVA.keyLeft(worldObj));
        AirDefenseDataSVA kd = AirDefenseDataSVA.get(worldObj);
        t.setInteger("KeyGen", kd == null ? 1 : kd.gen(ownerName));
        t.setInteger("CardPeriods", cardPeriods());
        // code-only wave: the mode (CO_*), the arming's seconds left, the options, the readiness line (items 16, 32, 38) and the
        // confirmation window's summary (item 27): the launchers by their rule, the tracks without the key's code (the owner's / his list's)
        AirDefenseDataSVA.KpInfo ci = info();
        long cnow = worldObj.getTotalWorldTime();
        t.setByte("Co", (byte) codeOnlyState());
        t.setInteger("CoArm", CodeOnlyMath.armLeft(ci.codeOnly, ci.armAt, cnow));
        t.setInteger("CoOpts", CodeOnlyMath.opts(ci.coOpts));
        t.setByte("CoReady", (byte) coReadiness());
        t.setBoolean("CoServer", ConfigSVA.adCodeOnly);
        t.setBoolean("CoSrvKey", ConfigSVA.adDayKey);               // review batch 3: the server's dayKey=false - the switch would be refused
        t.setBoolean("CoManual", ConfigSVA.adCodeOnlyManual);
        int[] byRoe = new int[RoeMath.ROES];
        for (TileEntityLauncherSVA l : launchers) {
            byRoe[Math.max(0, Math.min(RoeMath.ROES - 1, l.roe()))]++;
        }
        int noKey = 0, noKeyOwn = 0;
        int kn = dayCode(), kp = keyCode(AirDefenseDataSVA.keyPeriod(worldObj) - 1);
        for (AirDefenseSVA.Contact tr : tracks.values()) {
            if (tr.manned && !(tr.replied && (tr.code == kn && kn >= 0 || tr.code == kp && kp >= 0))) {
                noKey++;
                noKeyOwn += tr.rel != 0 ? 1 : 0;
            }
        }
        t.setIntArray("CoSum", new int[] {byRoe[RoeMath.HOLD], byRoe[RoeMath.VIOLATORS], byRoe[RoeMath.FOREIGN_ALL], byRoe[RoeMath.KP_MARKS], noKey, noKeyOwn});
        t.setBoolean("DayKey", dayKey);
        t.setBoolean("Alarm", alarm);
        t.setBoolean("Auto", auto);
        t.setBoolean("Powered", powered);
        t.setInteger("Energy", getEnergyStored());
        t.setInteger("EMax", getMaxEnergyStored());
        t.setString("Mode", ConfigSVA.adFires() ? "fire" : ConfigSVA.adEnabled ? "warn" : "off");
        t.setInteger("ZoneMax", ConfigSVA.adZoneMaxDistance);   // audit 2026-10-22: the zone editor's check before sending
        NBTTagList rl = new NBTTagList();
        for (TileEntityRadarSVA r : radars) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("X", r.xCoord);
            c.setInteger("Z", r.zCoord);
            c.setByte("K", (byte) r.kind());
            c.setBoolean("P", r.isPowered() && !r.isInvalid());
            c.setString("Key", r.key());
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
            Long g = info().grants.get(tr.vid);
            c.setInteger("Grant", g == null ? 0 : (int) Math.max(0, (g.longValue() - now) / 20));
            c.setBoolean("Hold", tr.held);
            c.setBoolean("Ident", tr.ident);
            c.setByte("St", (byte) tr.stage);
            c.setInteger("Tf", tr.toFire);
            c.setString("By", tr.engagedBy);
            c.setBoolean("Old", tr.saidOld);
            c.setByte("NR", (byte) tr.nr);
            c.setInteger("NRt", tr.nrLeft);
            c.setByte("Iff", (byte) tr.iff);
            c.setBoolean("Hand", tr.saidIff);
            c.setString("Apt", tr.airport);
            // code-only wave (item 39): classed by the code, the reason and the miss (RoeMath.ID_*), the misses in a row («ПРОВ
            // 1/2»), 7700's state and grace left, the relation (the grey ВЛ / ДР), the vehicle's owner (the track form)
            c.setBoolean("Co", tr.codeOnly);
            c.setByte("Idr", (byte) tr.idr);
            c.setByte("IdM", (byte) tr.idMiss);
            c.setByte("Cnf", (byte) tr.confirm.misses);
            c.setByte("EM", (byte) tr.em);
            c.setInteger("EMt", tr.emLeft);
            c.setByte("Rel", (byte) tr.rel);
            c.setString("Own", tr.owner);
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
        t.setString("Trust", com.sva.airdefense.AirportCodeMath.joinTrusted(trusted));
        t.setInteger("NRTimer", nrTimer() / 20);
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
        legacy = !t.hasKey("KpTrustV");                             // audit review: saved before the owner binding (migrate)
        for (int i = 0; i < wb.length && i < MAX_WHITE; i++) {
            String o = i < wo.tagCount() ? wo.getStringTagAt(i) : "";
            whiteBoard.add(Integer.valueOf(wb[i]));
            whiteOwner.add(legacy && o.length() == 0 ? AirportCodeMath.ANY_OWNER : o);
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
        trusted.clear();
        NBTTagList tl = t.getTagList("KpTrust", 8);
        for (int i = 0; i < tl.tagCount() && i < com.sva.airdefense.AirportCodeMath.MAX_TRUSTED; i++) {
            trusted.add(tl.getStringTagAt(i));
        }
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
        t.setInteger("KpTrustV", 2);                                // audit review: the owner binding (an older save - migrate)
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
        NBTTagList tl = new NBTTagList();
        for (String s : trusted) {
            tl.appendTag(new NBTTagString(s));
        }
        t.setTag("KpTrust", tl);
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
