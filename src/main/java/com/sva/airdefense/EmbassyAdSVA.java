package com.sva.airdefense;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.airport.EmbassyAtcSVA;
import com.sva.block.AirportDataSVA;
import com.sva.block.AtcServerSVA;
import com.sva.compat.EmbassyCompatSVA;
import com.sva.compat.EmbassyFieldSVA;
import com.sva.entity.EntitySeatSVA;
import com.sva.entity.EntityVehicleSVA;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.FakePlayer;

/**
 * PACKAGE E (docs/plan-embassy-airfield-addon.md §3.10): «ПВО посольства» - the server side of the Tuners' embassies' own air defence.
 * AirDefenseSVA.onTick calls tick(ticks) every server tick (the coordinator's hook; the work every LOOK_TICKS); clear() at the server's
 * stop (SVAMod). Only with EmbassyCompatSVA.on(), ConfigSVA.adEnabled and ConfigSVA.adEmbassy; the Overworld only.
 * <p>
 * For every airfield (EmbassyCompatSVA.fields) with a player within its adRadius + EYES: each EntityVehicleSVA in the bare circle
 * (EmbassyFieldSVA.inAdCircle) is looked at - its Look (EmbassyAdMath: airborneForAtc, adZoneAt == id, riders() each holdFire, the owner
 * for one with nobody aboard), the verdict acted on: A_WARN - requestWarn for each one aboard (the earliest end, EmbassyAdMath.turnEnd /
 * extraWarning), AD_WARN through EmbassyCompatSVA.tell to each one aboard, the HUD line (NetADSVA D_MSG «sva.emb.ad.hud.warn») and the
 * radio (airport.EmbassyAtcSVA.radio); A_FIRE - AD_FIRE once an engagement, the nearest post with a clear line (EmbassyAdMath.pickPost,
 * AirDefenseSVA.losClear from the gun) fires a burst as the add-on's AA gun does (BallisticsMath.gunAim, GUN_BURST x SHELL_DAMAGE every
 * GUN_PERIOD; AirDefenseSVA.Projectile from the gun with the post's 5 x 5 platform as its homeCells; AirDefenseSVA.INSTANCE.fire), the
 * RWR painted (AirDefenseSVA.paint T_LAUNCHER), the launch log line «EMB AA ...»; left the zone - AD_LEFT, the memory gone; 7700
 * (the transponder's code, XpdrMath.EMERGENCY, while it answers) - requestPass once an episode, AD_PASS / AD_PASS_USED, the pass's end
 * AD_PASS_OVER. Per machine memory in RAM only (by its vehicle key AirDefenseSVA.vehKey; the core's ledgers are per player by UUID).
 * <p>
 * Package E's bodies (2026-10-27):
 * <ul>
 * <li><b>What is judged</b>: only a machine that really flies (Movement.flies - the helicopters, planes, tiltrotors, the shuttle, the
 * ship, the liner; never the hoverbike, a buggy over a bump, the truck, the drill) and is airborneForAtc, not a wreck. A player on foot
 * or in armour / on a jetpack is no EntityVehicleSVA at all. On the ground in the zone - no fire ever, the memory kept (a warned machine
 * that landed and takes off again the same game day is a target at once; another day's warning is forgotten at the next look - review
 * 2026-10-27 - so the core's ledger gives that day's own); out of the zone (over a player's base too: adZoneAt -1) - AD_LEFT, forgotten.</li>
 * <li><b>Aboard</b>: the players of riders() (pilot, crew, passengers; a FakePlayer is nobody). Any one's hold (holds: the core's holdFire -
 * admitted, his 7700 pass, over a base - and a squad mate's cover only while that mate flies too) - no target (LAN: two aboard, either
 * admitted). Nobody aboard: the owner's rule
 * (EmbassyAdMath.ownerHolds, airdefense.embassyUnmanned) - the owner online in the Overworld and admitted holds it; the warning goes to
 * him (his requestWarn); an owner offline is warned by nobody and the seconds run from the first look.</li>
 * <li><b>The warning's seconds</b> (airdefense.embassyWarnSeconds; a machine's end the EARLIEST of its riders'): a rider's day's first
 * warning in the core's ledger (warnUntil 0 before requestWarn) - the seconds from now (turnEnd); back into the zone while they still
 * run - what is left of them (the add-on's RAM ledger by UUID|id, its day kept); airdefense.embassyWarnPerDay > 1 - that many more
 * warnings with new seconds (extraWarning on the RAM count; a server restart forgets the extra ones - the core's one stays); else the
 * end already past - the fire at once (WHY_REENTRY, no new AD_WARN). perDay 0 - no warning at all, the fire at once. AD_WARN (HUD,
 * radio) is said whenever a warning stage begins with seconds left, its chat line only to one with a new warning (sayWarn: the core's
 * line or, with other seconds / more warnings left today, the add-on's); the HUD line again every HUD_AGAIN while they run.</li>
 * <li><b>The fire</b>: the posts' guns (EmbassyFieldSVA.adPosts; the muzzle MUZZLE_DY over the gun block, + 0.5 across), each one at
 * most a burst every GUN_PERIOD, within GUN_RANGE, a clear line to the lead point; the shells are the add-on's (AirDefenseSVA.hit:
 * x airdefense.damageMultiplier, the hits summed into the launch log here - onHit); the shells still in the air go harmless the moment
 * the machine is no target (landed, held, left - AirDefenseSVA.disarm). Not airdefense.mode / requirePvp / fireOnUnmanned /
 * minDistanceFromSpawn / fireInProtectedRegions - those are the players' air defence's rules.</li>
 * <li><b>7700</b> (the transponder answering with XpdrMath.EMERGENCY, the pilot - AtcServerSVA.pilotOf: the driver's or the liner's first
 * officer's seat - a player; the pass's holder kept by UUID, its clock his passUntil): asked once an episode
 * (while the code stays 7700 and the machine is remembered), only when nothing else holds it (a pass is never spent for nothing):
 * PASS_GRANTED / PASS_RUNNING - held until the core's passUntil, then AD_PASS_OVER (and AD_FIRE again if it fires); PASS_USED -
 * AD_PASS_USED, still a target. RoeMath (the players' air defence: 7700 is АВАРИЯ, never a target) is not touched.</li>
 * </ul>
 * The zone editor's refusal (TileEntityCommandPostSVA op «zone»): underNeutralSky.
 */
public final class EmbassyAdSVA {

    public static final EmbassyAdSVA INSTANCE = new EmbassyAdSVA();
    /** The machines are looked at this often (ticks). */
    public static final int LOOK_TICKS = 5;
    /** An airfield is looked after while a player is within its adRadius + this (blocks across from the core). */
    public static final int EYES = 64;
    /** The embassies' guns' launcher keys begin so («emb:<id>:<post index>») - AirDefenseSVA.hit hands their hits to onHit. */
    public static final String LAUNCHER_PREFIX = "emb:";
    /** A machine not looked at for this long (ticks: unloaded, gone, nobody near its embassy) is forgotten without a word. */
    public static final int FORGET_TICKS = 200;
    /** The warning's HUD line again this often while its seconds run (ticks; the HUD keeps a line 5 s). */
    public static final int HUD_AGAIN = 100;
    /** The firing post paints the target's RWR this long (ticks) at each look of the fire. */
    public static final int PAINT_TICKS = 40;
    /** The muzzle over the gun block (blocks; the open cell over the gun, the core's ComponentEmbAirDefenceSC). */
    public static final double MUZZLE_DY = 1.5;
    /** The gun's hits go to the launch log once they pause this long, or this long after the first (ticks; TileEntityLauncherSVA's). */
    public static final int HITS_PAUSE = 20, HITS_EVERY = 100;
    /** The zone editor's refusal: fields whose core is farther than this from the zone's nearest reach are not asked (blocks). */
    public static final int NEUTRAL_REACH = 1024;
    /** The zone editor's refusal: the outline is asked every this many blocks (the neutral corridors are 40 across). */
    public static final double NEUTRAL_STEP = 8;
    /** The HUD colours (AirDefenseSVA.tell's): the warning and the fire, the pass, the zone left. */
    static final int RED = 0xF05050, AMBER = 0xF0B040, GREEN = 0x62E06A;
    /** The post's 5 x 5 platform, its parapet and corner nodes round the gun ({dx, dy, dz} from the gun block) - the shells leave through them. */
    static final int[][] PLATFORM = platform(2, -2, 1);

    /** One machine's memory (E may add fields). */
    public static final class Track {
        /** The embassy, the verdict last acted on (EmbassyAdMath.A_*), the warning (EmbassyAdMath.Look's warned / warnedAt / end / dist0 / h0). */
        public int embassy = -1, stage = EmbassyAdMath.A_NONE, why = EmbassyAdMath.WHY_NONE;
        public boolean warned, saidFire, pass, passAsked;
        public long warnedAt, end, seen, nextShot;
        public double dist0, h0;
        /** The 7700 pass's end (world ticks; the core's passUntil at the grant), the warning's HUD line last sent. */
        public long passEnd, hudAt;
        /** The post that fired last (adPosts index; -1 none yet this engagement) - its first burst and a change go to the launch log. */
        public int post = -1;
        /** World tests: the bursts fired at it, the shells that hit it (all its time in the memory). */
        public int bursts, hitsTotal;
        /** The hits not yet in the launch log: how many, the damage, the first's and the last's world time. */
        int hits;
        float hitDamage;
        long hitFirst, hitAt;
        /** The embassy's ICAO and the machine's «owner type B-n» for the log lines. */
        String icao = "", label = "";
        /**
         * Review 2026-10-27: the game day of the warning (day(w); -1 none) - another day's warning is forgotten at the next look (the core's
         * ledger gives a new one), and the 7700 pass's holder (UUID; "" none) - his own passUntil keeps its clock, whoever sits where.
         */
        public long warnDay = -1;
        public String passHolder = "";
    }

    /** vehKey -> its memory. */
    private final Map<String, Track> tracks = new HashMap<String, Track>();
    /** vehKey -> the embassy whose AD_NEAR it heard on this approach (ЛП-1; forgotten out of every circle). */
    private final Map<String, Integer> nearSaid = new HashMap<String, Integer>();
    /**
     * The add-on's warnings per player beyond the core's ledger (airdefense.embassyWarnPerDay, the seconds of the last one): "<uuid>|<id>"
     * -> {extra warnings given that day, the add-on's end of the last, the game day}. RAM only.
     */
    private final Map<String, long[]> ledger = new HashMap<String, long[]>();
    /** "<id>:<post index>" -> the world time that post may fire again. */
    private final Map<String, Long> postNext = new HashMap<String, Long>();
    /** World tests: vehKey -> the AD_* lines said about it (bits; debugSaid). At most ~256 machines, then started afresh. */
    private final Map<String, Integer> said = new HashMap<String, Integer>();
    /** World tests: "<uuid>|<hud key>" -> the HUD lines sent that player (debugHud; a test player has no connection). ~256, then afresh. */
    private final Map<String, Integer> hudSent = new HashMap<String, Integer>();
    private final Random rnd = new Random();
    private int errors;

    private EmbassyAdSVA() {
    }

    /** Every server tick (AirDefenseSVA.onTick). */
    public void tick(int ticks) {
        if (ticks % LOOK_TICKS != 0) {
            return;
        }
        try {
            World w = DimensionManager.getWorld(0);
            if (w == null) {
                return;
            }
            if (!active()) {
                if (!tracks.isEmpty() || !ledger.isEmpty()) {
                    clear();                                        // switched off: nothing remembered for a later switch on
                }
                return;
            }
            look(w);
        } catch (RuntimeException e) {
            if (errors++ < 3) {                                     // the rest of the air defence's tick goes on
                System.out.println("[SVA-AD] embassy air defence: " + e);
                e.printStackTrace(System.out);
            }
        }
    }

    /** The server stopped (SVAMod): every memory gone. */
    public void clear() {
        tracks.clear();
        ledger.clear();
        postNext.clear();
        said.clear();
        hudSent.clear();
    }

    /** World tests: a machine's memory now (null none). */
    public Track debugTrack(EntityVehicleSVA v) {
        return v == null ? null : tracks.get(AirDefenseSVA.vehKey(v));
    }

    /** World tests: one look at world `w` now, out of the tick's schedule. */
    public void debugLook(World w) {
        if (w != null && !w.isRemote && w.provider != null && w.provider.dimensionId == 0 && active()) {
            look(w);
        }
    }

    /** The embassies' air defence works now (airdefense.enabled, airdefense.embassyAd, the core's API). */
    public static boolean active() {
        return ConfigSVA.adEnabled && ConfigSVA.adEmbassy && EmbassyCompatSVA.on();
    }

    // ------------------------------------------------------------------ the look

    private void look(World w) {
        long now = w.getTotalWorldTime();
        List<EmbassyFieldSVA> all = EmbassyCompatSVA.fields(w);
        if (!all.isEmpty() || !tracks.isEmpty()) {
            Set<Integer> live = new HashSet<Integer>();
            for (EmbassyFieldSVA f : all) {
                if (f.adRadius > 0 && playerNear(w, f)) {
                    live.add(Integer.valueOf(f.id));
                }
            }
            List<?> es = w.loadedEntityList;
            for (int i = 0; i < es.size(); i++) {                   // by index: a look never spawns, but never trips over one either
                Object o = es.get(i);
                if (!(o instanceof EntityVehicleSVA)) {
                    continue;
                }
                EntityVehicleSVA v = (EntityVehicleSVA) o;
                String key = AirDefenseSVA.vehKey(v);
                Track t = tracks.get(key);
                if (v.isDead || t == null && !v.getVehicleType().movement.flies) {
                    continue;
                }
                EmbassyFieldSVA f = circleOf(all, v.posX, v.posZ);
                if (f == null) {
                    nearSaid.remove(key);                           // the next approach is warned again (ЛП-1)
                    if (t != null) {
                        left(w, v, t, now);                         // out of every embassy's circle
                    }
                } else if (live.contains(Integer.valueOf(f.id))) {
                    look(w, f, v, t, key, now);
                }                                                   // nobody near that embassy: left as it is
            }
        }
        prune(now, day(w));
    }

    /**
     * ЛП-1 of the core audit 2026-11-04: a machine in the air in embassy `f`'s warning circle (adWarnZoneAt) but not where its guns fire
     * (adZoneAt) with nobody admitted aboard hears AD_NEAR once per approach (chat, HUD, radio) - no clock, no fire. An older core
     * without adWarnZoneAt: never (its zone is the whole circle).
     */
    private void near(World w, EmbassyFieldSVA f, EntityVehicleSVA v, String key) {
        if (EmbassyCompatSVA.adWarnZoneAt(w, v.posX, v.posZ) != f.id) {
            return;
        }
        Integer was = nearSaid.get(key);
        if (was != null && was.intValue() == f.id) {
            return;                                                 // said on this approach already
        }
        List<EntityPlayer> aboard = aboard(v);
        if (aboard.isEmpty()) {
            return;
        }
        for (EntityPlayer p : aboard) {
            if (EmbassyCompatSVA.admitted(p, f.id)) {
                return;
            }
        }
        nearSaid.put(key, Integer.valueOf(f.id));
        say(w, f.id, v, aboard, EmbassyCompatSVA.AD_NEAR, AMBER, "sva.emb.ad.hud.near");
    }

    /** One machine in field `f`'s circle (t: its memory, null none). */
    private void look(World w, EmbassyFieldSVA f, EntityVehicleSVA v, Track t, String key, long now) {
        if (t != null && t.embassy != f.id) {
            left(w, v, t, now);                                     // another embassy's circle now
            t = null;
        }
        EmbassyAdMath.Look k = new EmbassyAdMath.Look();
        k.now = now;
        k.airborne = v.getVehicleType().movement.flies && !v.isWrecked() && v.airborneForAtc();
        k.inZone = (k.airborne || t != null) && EmbassyCompatSVA.adZoneAt(w, v.posX, v.posZ) == f.id;
        if (t == null && k.airborne && !k.inZone) {
            near(w, f, v, key);                                     // ЛП-1: the circle beyond the guns - only «approaching»
        }
        if (t == null && !(k.airborne && k.inZone)) {
            return;                                                 // nothing to judge, nothing to remember
        }
        if (t == null) {
            t = new Track();
            t.embassy = f.id;
            t.icao = AtcServerSVA.embassyIcao(w, AirportDataSVA.get(w), f.id);   // the virtual tower's unique code (XTAB...)
            tracks.put(key, t);
        }
        t.seen = now;
        t.label = label(v);
        flushHits(t, now, false);
        if (t.warned && t.warnDay != day(w)) {
            forgetWarning(v, t);                                    // another game day's warning: today's is the core's ledger's again
        }
        List<EntityPlayer> aboard = aboard(v);
        k.manned = !aboard.isEmpty();
        EntityPlayer owner = k.manned ? null : ownerIn(w, v);
        List<EntityPlayer> to = k.manned ? aboard : one(owner);
        if (t.pass) {
            EntityPlayer holder = byUuid(aboard, t.passHolder);     // the core's ledger keeps the pass's clock - its holder's own
            if (holder != null) {
                t.passEnd = Math.min(t.passEnd, EmbassyCompatSVA.passUntil(holder, f.id));   // cut short there: over now
            } else if (k.manned) {
                t.passEnd = Math.min(t.passEnd, now);               // others aboard without him: not his pass's machine any more
            }                                                       // nobody aboard (stepped out on the field): the end as granted
        }
        if (t.pass && now >= t.passEnd) {
            t.pass = false;
            t.saidFire = false;                                     // the fire again is said again
            say(w, f.id, v, aboard, EmbassyCompatSVA.AD_PASS_OVER, AMBER, "sva.emb.ad.hud.passOver");
        }
        boolean squawk = AirDefenseSVA.xpdrOn(v) && v.state.xpdr.code == XpdrMath.EMERGENCY;
        if (!squawk) {
            t.passAsked = false;                                    // the episode is over: the next 7700 asks again
        }
        if (k.airborne && k.inZone) {
            if (k.manned) {
                EntityPlayer pl = t.pass ? null : pilot(v);
                long running = pl == null ? 0L : EmbassyCompatSVA.passUntil(pl, f.id);
                if (running > now) {                                // back in with his pass still running: its end is said
                    t.pass = true;
                    t.passEnd = running;
                    t.passHolder = pl.getUniqueID().toString();
                    t.passAsked |= squawk;
                }
                for (EntityPlayer p : aboard) {
                    if (holds(p, f.id, now)) {
                        k.anyHold = true;
                        break;
                    }
                }
                EntityPlayer pilot = pilot(v);
                if (!k.anyHold && squawk && pilot != null && !t.passAsked) {
                    t.passAsked = true;
                    int answer = EmbassyCompatSVA.requestPass(pilot, f.id);
                    if (answer == EmbassyCompatSVA.PASS_GRANTED || answer == EmbassyCompatSVA.PASS_RUNNING) {
                        t.passEnd = EmbassyCompatSVA.passUntil(pilot, f.id);
                        t.pass = t.passEnd > now;
                        t.passHolder = pilot.getUniqueID().toString();
                        k.anyHold = t.pass;
                    }
                    int line = EmbassyAdMath.passLine(answer);
                    if (line == EmbassyCompatSVA.AD_PASS) {
                        say(w, f.id, v, aboard, line, AMBER, "sva.emb.ad.hud.pass", String.valueOf(EmbassyAdMath.secondsLeft(t.passEnd, now)));
                    } else if (line == EmbassyCompatSVA.AD_PASS_USED) {
                        say(w, f.id, v, aboard, line, AMBER, "sva.emb.ad.hud.passUsed");
                    }
                }
            } else {
                k.ownerHold = EmbassyAdMath.ownerHolds(ConfigSVA.adEmbassyUnmanned, owner != null,
                        owner != null && EmbassyCompatSVA.admitted(owner, f.id));
            }
        }
        k.warned = t.warned;
        k.warnedAt = t.warnedAt;
        k.end = t.end;
        k.dist0 = t.dist0;
        k.h0 = t.h0;
        k.dist = f.fromCore(v.posX, v.posZ);
        k.h = v.posY - f.floorY;
        k.closeBlocks = ConfigSVA.adEmbassyCloseBlocks;
        k.minHeight = ConfigSVA.adEmbassyMinHeight;
        int a = EmbassyAdMath.verdict(k);
        if (a == EmbassyAdMath.A_WARN) {
            warn(w, f, v, t, k, aboard, owner, now);
            a = EmbassyAdMath.verdict(k);                           // TURN, or FIRE at once (warned today / no warnings)
        }
        int was = t.stage;
        t.stage = a;
        if (a != EmbassyAdMath.A_FIRE && was == EmbassyAdMath.A_FIRE) {
            AirDefenseSVA.INSTANCE.disarm(v.getEntityId(), LAUNCHER_PREFIX);   // no target now: its shells in the air hurt nobody
        }
        if (a == EmbassyAdMath.A_NONE) {
            if (!k.inZone) {
                left(w, v, t, now);                                 // out of the zone (or over a player's base): forgotten
            }                                                       // on the ground in the zone: never a shot, the memory kept
        } else if (a == EmbassyAdMath.A_TURN) {
            if (now - t.hudAt >= HUD_AGAIN) {
                t.hudAt = now;
                for (EntityPlayer p : to) {
                    hud(p, RED, "sva.emb.ad.hud.warn", String.valueOf(EmbassyAdMath.secondsLeft(t.end, now)));
                }
            }
        } else if (a == EmbassyAdMath.A_FIRE) {
            t.why = EmbassyAdMath.why(k);
            if (!t.saidFire) {
                t.saidFire = true;
                say(w, f.id, v, to, EmbassyCompatSVA.AD_FIRE, RED, "sva.emb.ad.hud.fire");
            }
            fire(w, f, v, t, now);
        }                                                           // A_HOLD: no fire, the memory kept (the pass over - a target again)
    }

    /** A_WARN: the warning asked for each one aboard (or the owner), its seconds into the memory and the look; AD_WARN said with seconds left. */
    private void warn(World w, EmbassyFieldSVA f, EntityVehicleSVA v, Track t, EmbassyAdMath.Look k, List<EntityPlayer> aboard,
            EntityPlayer owner, long now) {
        long end;
        List<EntityPlayer> to = k.manned ? aboard : one(owner);
        int[] kinds = new int[to.size()];
        if (EmbassyAdMath.noWarnings(ConfigSVA.adEmbassyWarnPerDay)) {
            end = 0;                                                // airdefense.embassyWarnPerDay 0: the fire at once
        } else if (!to.isEmpty()) {
            long[] ends = new long[to.size()];
            for (int i = 0; i < ends.length; i++) {
                long[] r = warnOne(w, to.get(i), f.id, now);
                ends[i] = r[0];
                kinds[i] = (int) r[1];
            }
            end = EmbassyAdMath.earliest(ends);
        } else {
            end = now + Math.max(1, ConfigSVA.adEmbassyWarnSeconds) * (long) EmbassyAdMath.TICKS;   // owner offline: nobody to warn
        }
        t.warned = k.warned = true;
        t.warnedAt = k.warnedAt = now;
        t.warnDay = day(w);
        t.end = k.end = end;
        t.dist0 = k.dist0 = k.dist;
        t.h0 = k.h0 = k.h;
        t.why = EmbassyAdMath.WHY_NONE;
        if (end > now) {
            t.hudAt = now;
            sayWarn(w, f, v, to, kinds, EmbassyAdMath.secondsLeft(end, now));
        }
    }

    /** warnOne's kinds: no seconds for him (admitted / warned today and past), a new warning (the core's / the add-on's extra), back in while they run. */
    static final int W_NONE = 0, W_NEW = 1, W_EXTRA = 2, W_LEFT = 3;

    /**
     * One player's warning at embassy `id` now: {the end of his seconds (world ticks; 0 - he needs none), its kind W_*}. The core's ledger
     * first (requestWarn - one a player an embassy a game day), the add-on's seconds and extra warnings by its RAM ledger (see the class).
     */
    private long[] warnOne(World w, EntityPlayer p, int id, long now) {
        int secs = ConfigSVA.adEmbassyWarnSeconds, perDay = ConfigSVA.adEmbassyWarnPerDay;
        long day = day(w);
        String key = p.getUniqueID() + "|" + id;
        long before = EmbassyCompatSVA.warnUntil(p, id);
        long core = EmbassyCompatSVA.requestWarn(p, id);
        if (core <= 0) {
            return new long[] {0, W_NONE};                          // admitted / not served: he needs none
        }
        long[] r = ledger.get(key);
        if (r != null && r[2] != day) {
            r = null;                                               // another day's
        }
        if (before <= 0) {                                          // the day's first in the core's ledger: the seconds from now
            long end = EmbassyAdMath.turnEnd(core, now, secs);
            ledger.put(key, new long[] {0, end, day});
            return new long[] {end, end > now ? W_NEW : W_NONE};
        }
        if (r != null && r[1] > now) {
            return new long[] {r[1], W_LEFT};                       // back in while they still run: what is left of them
        }
        int extra = r == null ? 0 : (int) r[0];
        if (EmbassyAdMath.extraWarning(true, extra, perDay)) {
            long end = now + Math.max(1, secs) * (long) EmbassyAdMath.TICKS;
            ledger.put(key, new long[] {extra + 1, end, day});
            return new long[] {end, W_EXTRA};
        }
        return new long[] {r != null ? r[1] : EmbassyAdMath.turnEnd(core, now, secs), W_NONE};   // warned today: past - no new seconds
    }

    /**
     * AD_WARN with `left` seconds to each of `to` (kinds: warnOne's) - review 2026-10-27: the chat line only with a NEW warning (back in
     * while the seconds run - the HUD and the radio alone), and it never promises what the add-on does not do: the core's line (its fixed
     * WARN_SECONDS, «no second warning today») only when the seconds and the day's count are the core's; else the add-on's own line
     * sva.emb.ad.warnChat / warnChatMore with the real seconds. The HUD line to each, the radio once.
     */
    private void sayWarn(World w, EmbassyFieldSVA f, EntityVehicleSVA v, List<EntityPlayer> to, int[] kinds, int left) {
        String cs = AtcServerSVA.callsign(v);
        int perDay = ConfigSVA.adEmbassyWarnPerDay;
        for (int i = 0; i < to.size(); i++) {
            EntityPlayer p = to.get(i);
            int kind = i < kinds.length ? kinds[i] : W_NONE;
            if (kind == W_NEW || kind == W_EXTRA) {
                long[] r = ledger.get(p.getUniqueID() + "|" + f.id);
                boolean more = EmbassyAdMath.extraWarning(true, r == null ? 0 : (int) r[0], perDay);
                if (kind == W_NEW && !more && left == EmbassyCompatSVA.warnSeconds()) {
                    EmbassyCompatSVA.tell(p, f.id, EmbassyCompatSVA.AD_WARN, cs);
                } else {
                    tellWarn(p, f, cs, left, more);
                }
            }
            hud(p, RED, "sva.emb.ad.hud.warn", String.valueOf(left));
        }
        EmbassyAtcSVA.radio(w, v, f.id, EmbassyCompatSVA.AD_WARN, cs);
        noteSaid(v, EmbassyCompatSVA.AD_WARN);
    }

    /** The add-on's own AD_WARN chat line (red): the embassy, the callsign, the real seconds, the level that admits; `more` - not the day's last. */
    private static void tellWarn(EntityPlayer p, EmbassyFieldSVA f, String cs, int secs, boolean more) {
        if (!(p instanceof EntityPlayerMP) || p instanceof FakePlayer || ((EntityPlayerMP) p).playerNetServerHandler == null) {
            return;
        }
        IChatComponent c = new ChatComponentTranslation(more ? "sva.emb.ad.warnChatMore" : "sva.emb.ad.warnChat", f.name(), cs, String.valueOf(secs),
                new ChatComponentTranslation("sc.settle.rep." + EmbassyCompatSVA.LEVEL_KEYS[EmbassyCompatSVA.ACQUAINTANCE]));
        c.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED));
        p.addChatComponentMessage(c);
    }

    /** Another game day's warning forgotten (the next look in the air asks the core's ledger again); its fire, if any, over. */
    private static void forgetWarning(EntityVehicleSVA v, Track t) {
        if (t.stage == EmbassyAdMath.A_FIRE) {
            AirDefenseSVA.INSTANCE.disarm(v.getEntityId(), LAUNCHER_PREFIX);
        }
        t.warned = false;
        t.saidFire = false;
        t.warnedAt = t.end = 0;
        t.dist0 = t.h0 = 0;
        t.warnDay = -1;
        t.why = EmbassyAdMath.WHY_NONE;
        t.stage = EmbassyAdMath.A_NONE;
        t.post = -1;
    }

    /** Out of the zone / the circle: AD_LEFT if it was warned or fired on, its shells harmless, its hits logged, the memory gone. */
    private void left(World w, EntityVehicleSVA v, Track t, long now) {
        flushHits(t, now, true);
        if (t.stage == EmbassyAdMath.A_FIRE) {
            AirDefenseSVA.INSTANCE.disarm(v.getEntityId(), LAUNCHER_PREFIX);
        }
        if (t.warned || t.saidFire) {
            List<EntityPlayer> aboard = aboard(v);
            say(w, t.embassy, v, aboard.isEmpty() ? one(ownerIn(w, v)) : aboard, EmbassyCompatSVA.AD_LEFT, GREEN, "sva.emb.ad.hud.left");
        }
        tracks.remove(AirDefenseSVA.vehKey(v));
    }

    // ------------------------------------------------------------------ the fire

    /** A_FIRE: the nearest ready post with a clear line fires a burst (GUN_PERIOD a post, a machine); the RWR painted from it. */
    private void fire(World w, EmbassyFieldSVA f, EntityVehicleSVA v, Track t, long now) {
        int[][] posts = f.adPosts();
        if (posts.length == 0) {
            return;
        }
        double[] c = AirDefenseSVA.centre(v), vel = AirDefenseSVA.INSTANCE.velocity(v);
        double[][] mz = new double[posts.length][], aim = new double[posts.length][];
        boolean[] los = new boolean[posts.length];
        int nearest = -1;
        double nd = Double.MAX_VALUE;
        for (int i = 0; i < posts.length; i++) {
            if (posts[i] == null || posts[i].length < 3) {
                continue;
            }
            mz[i] = new double[] {posts[i][0] + 0.5, posts[i][1] + MUZZLE_DY, posts[i][2] + 0.5};
            double d = BallisticsMath.dist(mz[i][0], mz[i][1], mz[i][2], c[0], c[1], c[2]);
            if (d < nd) {
                nd = d;
                nearest = i;
            }
            if (now < t.nextShot || d > BallisticsMath.GUN_RANGE || !ready(f.id, i, now)) {
                continue;
            }
            aim[i] = BallisticsMath.gunAim(mz[i][0], mz[i][1], mz[i][2], c[0], c[1], c[2], vel[0], vel[1], vel[2], BallisticsMath.GUN_SPEED,
                    BallisticsMath.SHELL_LIFE);
            los[i] = aim[i] != null && AirDefenseSVA.losClear(w, mz[i][0], mz[i][1], mz[i][2], aim[i][0], aim[i][1], aim[i][2]);
        }
        int n = EmbassyAdMath.pickPost(mz, los, c[0], c[1], c[2], BallisticsMath.GUN_RANGE);
        int paintFrom = n >= 0 ? n : nearest;
        if (paintFrom >= 0) {
            AirDefenseSVA.INSTANCE.paint(v, RadarMath.T_LAUNCHER, mz[paintFrom][0], mz[paintFrom][2], now + PAINT_TICKS);
        }
        if (n < 0) {
            return;                                                 // out of the guns' reach / no clear line / reloading
        }
        burst(w, v, f.id, n, posts[n], mz[n], aim[n]);
        t.nextShot = now + BallisticsMath.GUN_PERIOD;
        t.bursts++;
        postNext.put(f.id + ":" + n, Long.valueOf(now + BallisticsMath.GUN_PERIOD));
        if (t.post != n) {
            t.post = n;
            AirDefenseSVA.INSTANCE.launchLog(String.format("EMB AA %s post %d (%d %d %d): fire at %s %s %s - %s", t.icao, Integer.valueOf(n),
                    Integer.valueOf(posts[n][0]), Integer.valueOf(posts[n][1]), Integer.valueOf(posts[n][2]), owner(v), v.getVehicleType().key,
                    AirDefenseSVA.xpdrOn(v) ? XpdrMath.format(v.state.xpdr.code) : "----", EmbassyAdMath.WHY_KEYS[t.why]));
        }
    }

    /** One burst from post `n`'s muzzle `m` at the lead point `aim` - the add-on's AA gun's (TileEntityLauncherSVA.burst). */
    private void burst(World w, EntityVehicleSVA v, int id, int n, int[] gun, double[] m, double[] aim) {
        double[] d = BallisticsMath.dir(m[0], m[1], m[2], aim[0], aim[1], aim[2]);
        for (int i = 0; i < BallisticsMath.GUN_BURST; i++) {
            double[] s = BallisticsMath.spread(d, BallisticsMath.GUN_SPREAD, rnd);    // the post sees its own sky: precise
            AirDefenseSVA.Projectile pr = new AirDefenseSVA.Projectile();
            pr.x = m[0];
            pr.y = m[1];
            pr.z = m[2];
            pr.vx = s[0] * BallisticsMath.GUN_SPEED;
            pr.vy = s[1] * BallisticsMath.GUN_SPEED;
            pr.vz = s[2] * BallisticsMath.GUN_SPEED;
            pr.life = BallisticsMath.SHELL_LIFE;
            pr.target = v.getEntityId();
            pr.dim = w.provider.dimensionId;
            pr.damage = BallisticsMath.SHELL_DAMAGE;
            pr.launcher = LAUNCHER_PREFIX + id + ":" + n;
            pr.hx = gun[0];
            pr.hy = gun[1];
            pr.hz = gun[2];
            pr.homeCells = PLATFORM;                                // out through the post's own platform and parapet
            AirDefenseSVA.INSTANCE.fire(pr);
        }
        w.playSoundEffect(m[0], m[1], m[2], "fireworks.blast_far", 3F, 0.6F);
        if (w instanceof WorldServer) {
            ((WorldServer) w).func_147487_a("largesmoke", m[0], m[1], m[2], 4, 0.25, 0.15, 0.25, 0.01);
        }
    }

    /** Post `n` of embassy `id` may fire now (each post a burst every GUN_PERIOD, whichever machine it was at). */
    private boolean ready(int id, int n, long now) {
        Long at = postNext.get(id + ":" + n);
        return at == null || now >= at.longValue() || at.longValue() - now > BallisticsMath.GUN_PERIOD;
    }

    /** AirDefenseSVA.hit: a shell of an embassy's post (its launcher key begins with LAUNCHER_PREFIX) hit `target` for `damage`. */
    public void onHit(String launcher, EntityVehicleSVA target, float damage) {
        Track t = target == null ? null : tracks.get(AirDefenseSVA.vehKey(target));
        if (t == null) {
            AirDefenseSVA.INSTANCE.launchLog(String.format("EMB AA %s: hit %s (-%d hull)", launcher, target == null ? "-" : label(target),
                    Integer.valueOf(Math.round(damage))));
            return;
        }
        long now = target.worldObj.getTotalWorldTime();
        if (t.hits == 0) {
            t.hitFirst = now;
        }
        t.hits++;
        t.hitsTotal++;
        t.hitDamage += damage;
        t.hitAt = now;
    }

    /** The hits summed into the launch log once they pause (or every HITS_EVERY; `all` - now). */
    private static void flushHits(Track t, long now, boolean all) {
        if (t.hits == 0 || !all && now - t.hitAt < HITS_PAUSE && now - t.hitFirst < HITS_EVERY && now >= t.hitFirst) {
            return;
        }
        AirDefenseSVA.INSTANCE.launchLog(String.format("EMB AA %s: hit %s x%d (-%d hull)", t.icao, t.label, Integer.valueOf(t.hits),
                Integer.valueOf(Math.round(t.hitDamage))));
        t.hits = 0;
        t.hitDamage = 0;
    }

    // ------------------------------------------------------------------ the zone editor (TileEntityCommandPostSVA op «zone»)

    /**
     * A forbidden zone `z` of world `w` that would lie under an embassy's neutral sky - the players' air defence never fires there, so no
     * forbidden zone may be drawn over it: its middle or a point of its outline (every NEUTRAL_STEP) is neutral (EmbassyCompatSVA.neutralAt
     * - the launchers' own rule, a player's base left out), or it holds a field's core, a corner or the middle of its protected zone's box
     * or of an approach corridor (EmbassyFieldSVA.approachBoxes) - a zone drawn round the field. Other zone types: never.
     */
    public static boolean underNeutralSky(World w, ZoneMath.Zone z) {
        if (z == null || z.type != ZoneMath.FORBIDDEN || w == null || w.isRemote || !EmbassyCompatSVA.on()) {
            return false;
        }
        double[] m = ZoneMath.middle(z);
        double reach = ZoneMath.farthest(z, m[0], m[1]);
        boolean near = false;
        for (EmbassyFieldSVA f : EmbassyCompatSVA.fields(w)) {
            if (f.fromCore(m[0], m[1]) - reach > NEUTRAL_REACH) {
                continue;
            }
            near = true;
            if (ZoneMath.contains2D(z, f.coreX + 0.5, f.coreZ + 0.5) || boxIn(z, f.zoneBox())) {
                return true;
            }
            for (int[] b : f.approachBoxes()) {
                if (boxIn(z, b)) {
                    return true;
                }
            }
        }
        if (!near) {
            return false;
        }
        if (EmbassyCompatSVA.neutralAt(w, m[0], 64, m[1])) {
            return true;
        }
        for (double[] p : EmbassyAdMath.alongOutline(ZoneMath.outline(z, 64), NEUTRAL_STEP)) {
            if (EmbassyCompatSVA.neutralAt(w, p[0], 64, p[1])) {
                return true;
            }
        }
        return false;
    }

    /** A corner or the middle of box {minX, minZ, maxX, maxZ} (inclusive blocks) lies in the zone. */
    private static boolean boxIn(ZoneMath.Zone z, int[] b) {
        if (b == null || b.length < 4) {
            return false;
        }
        double[][] pts = {{b[0] + 0.5, b[1] + 0.5}, {b[2] + 0.5, b[1] + 0.5}, {b[2] + 0.5, b[3] + 0.5}, {b[0] + 0.5, b[3] + 0.5},
            {(b[0] + b[2]) / 2.0 + 0.5, (b[1] + b[3]) / 2.0 + 0.5}};
        for (double[] p : pts) {
            if (ZoneMath.contains2D(z, p[0], p[1])) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ inside

    /** Some player (not a FakePlayer) of `w` is within the field's adRadius + EYES across from its core. */
    private static boolean playerNear(World w, EmbassyFieldSVA f) {
        double r = f.adRadius + EYES;
        for (Object o : w.playerEntities) {
            if (o instanceof EntityPlayer && !(o instanceof FakePlayer) && f.fromCore(((EntityPlayer) o).posX, ((EntityPlayer) o).posZ) <= r) {
                return true;
            }
        }
        return false;
    }

    /** The field whose bare air defence circle holds (x, z) - the nearest core of two (null: none). */
    private static EmbassyFieldSVA circleOf(List<EmbassyFieldSVA> all, double x, double z) {
        EmbassyFieldSVA best = null;
        double bd = Double.MAX_VALUE;
        for (EmbassyFieldSVA f : all) {
            double d = f.fromCore(x, z);
            if (f.inAdCircle(x, z) && d < bd) {
                bd = d;
                best = f;
            }
        }
        return best;
    }

    /** The players aboard (pilot, crew, passengers; a FakePlayer is nobody). */
    private static List<EntityPlayer> aboard(EntityVehicleSVA v) {
        List<EntityPlayer> out = new ArrayList<EntityPlayer>();
        for (Entity e : v.riders()) {
            if (e instanceof EntityPlayer && !(e instanceof FakePlayer) && !out.contains(e)) {
                out.add((EntityPlayer) e);
            }
        }
        return out;
    }

    /**
     * The player at the controls (the one whose 7700 it is): the driver, the liner's first officer's seat too (AtcServerSVA.pilotOf -
     * review 2026-10-27: a liner flown from the right seat squawks 7700 as well), null none.
     */
    private static EntityPlayer pilot(EntityVehicleSVA v) {
        return AtcServerSVA.pilotOf(v);
    }

    /** The player of UUID `u` among `aboard` (null: not aboard / none). */
    private static EntityPlayer byUuid(List<EntityPlayer> aboard, String u) {
        if (u == null || u.length() == 0) {
            return null;
        }
        for (EntityPlayer p : aboard) {
            if (u.equals(p.getUniqueID().toString())) {
                return p;
            }
        }
        return null;
    }

    /**
     * One aboard holds the fire at embassy `id` (the core's holdFire: admitted, his 7700 pass running, an admitted squad mate near, over a
     * player's base) - review 2026-10-27, the user's wording «летящий в одном звене»: a squad mate's cover only while that mate is in the air
     * too (an admitted mate standing on the apron covers nobody flying round it).
     */
    static boolean holds(EntityPlayer p, int id, long now) {
        if (!EmbassyCompatSVA.holdFire(p, id)) {
            return false;
        }
        if (EmbassyCompatSVA.admitted(p, id) || EmbassyCompatSVA.passUntil(p, id) > now) {
            return true;
        }
        EntityPlayer m = EmbassyCompatSVA.escortOf(p, id);
        return m == null || inAir(m);                               // none: held for the base under it; a mate: only one who flies
    }

    /** A player in the air: in a machine that is (airborneForAtc), or himself off the ground (armour, a jetpack) 3 blocks clear below. */
    static boolean inAir(EntityPlayer p) {
        Entity r = p.ridingEntity;
        EntityVehicleSVA v = r instanceof EntityVehicleSVA ? (EntityVehicleSVA) r : r instanceof EntitySeatSVA ? ((EntitySeatSVA) r).getParent() : null;
        if (v != null) {
            return !v.isDead && v.airborneForAtc();
        }
        if (r != null || p.onGround || p.isInWater() || p.worldObj == null) {
            return false;
        }
        int x = MathHelper.floor_double(p.posX), y = MathHelper.floor_double(p.boundingBox.minY), z = MathHelper.floor_double(p.posZ);
        for (int i = 1; i <= 3; i++) {
            if (!p.worldObj.isAirBlock(x, y - i, z)) {
                return false;
            }
        }
        return true;
    }

    /** The machine's owner online in the same world (null: offline, elsewhere, none - not admitted then). */
    private static EntityPlayer ownerIn(World w, EntityVehicleSVA v) {
        String id = v.state.ownerId;
        if (id == null || id.length() == 0) {
            return null;
        }
        for (Object o : w.playerEntities) {
            if (o instanceof EntityPlayerMP && !(o instanceof FakePlayer) && id.equals(((EntityPlayer) o).getUniqueID().toString())) {
                return (EntityPlayer) o;
            }
        }
        return null;
    }

    private static List<EntityPlayer> one(EntityPlayer p) {
        List<EntityPlayer> out = new ArrayList<EntityPlayer>();
        if (p != null) {
            out.add(p);
        }
        return out;
    }

    /**
     * Line `line` (EmbassyCompatSVA.AD_*) of embassy `id` about machine `v`: the core's chat line to each of `to` (with its callsign), the
     * add-on's HUD line `hudKey` to each, the same on the embassy tower's radio (EmbassyAtcSVA: the datalink log, when it has a session).
     */
    private void say(World w, int id, EntityVehicleSVA v, List<EntityPlayer> to, int line, int colour, String hudKey, Object... args) {
        String cs = AtcServerSVA.callsign(v);
        for (EntityPlayer p : to) {
            EmbassyCompatSVA.tell(p, id, line, cs);
            hud(p, colour, hudKey, args);
        }
        EmbassyAtcSVA.radio(w, v, id, line, cs);
        noteSaid(v, line);
    }

    /** World tests' record: line `line` said about machine `v`. */
    private void noteSaid(EntityVehicleSVA v, int line) {
        String key = AirDefenseSVA.vehKey(v);
        if (said.size() > 256 && !said.containsKey(key)) {
            said.clear();
        }
        Integer was = said.get(key);
        said.put(key, Integer.valueOf((was == null ? 0 : was.intValue()) | 1 << line));
    }

    /**
     * World tests: the lines (bits 1 << EmbassyCompatSVA.AD_*) said about machine `v` since debugUnsay (a test player without a
     * connection gets neither the core's chat line nor the HUD line - this is what was said all the same).
     */
    public int debugSaid(EntityVehicleSVA v) {
        Integer m = v == null ? null : said.get(AirDefenseSVA.vehKey(v));
        return m == null ? 0 : m.intValue();
    }

    /** World tests: forgets what was said about machine `v`. */
    public void debugUnsay(EntityVehicleSVA v) {
        if (v != null) {
            said.remove(AirDefenseSVA.vehKey(v));
        }
    }

    /** World tests: the HUD lines of lang key `key` sent player `p` (counted even when it has no connection; never a FakePlayer). */
    public int debugHud(EntityPlayer p, String key) {
        Integer n = p == null ? null : hudSent.get(p.getUniqueID() + "|" + key);
        return n == null ? 0 : n.intValue();
    }

    /** The HUD line alone (the chat line is the core's) - a real player with a connection only. */
    private static void hud(EntityPlayer p, int colour, String key, Object... args) {
        if (p instanceof EntityPlayerMP && !(p instanceof FakePlayer)) {
            AirDefenseSVA.INSTANCE.hud((EntityPlayerMP) p, colour, key, args);
            INSTANCE.noteHud(p, key);
        }
    }

    private void noteHud(EntityPlayer p, String key) {
        if (hudSent.size() > 256) {
            hudSent.clear();
        }
        String k = p.getUniqueID() + "|" + key;
        Integer n = hudSent.get(k);
        hudSent.put(k, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
    }

    private static String owner(EntityVehicleSVA v) {
        String o = v.getOwnerName();
        return o == null || o.length() == 0 ? "-" : o;
    }

    /** «owner type B-n» for the log. */
    private static String label(EntityVehicleSVA v) {
        return owner(v) + " " + v.getVehicleType().key + " B-" + v.getBoardNumber();
    }

    /** The core's game day (SettlementSC.day: the world's time of day / 24000) - the RAM ledger's day. */
    private static long day(World w) {
        return Math.max(0L, w.getWorldTime()) / 24000L;
    }

    /** The memories not looked at for FORGET_TICKS (their hits logged), the ledger's other days, the posts' old reloads. */
    private void prune(long now, long day) {
        for (Iterator<Map.Entry<String, Track>> it = tracks.entrySet().iterator(); it.hasNext();) {
            Track t = it.next().getValue();
            if (now - t.seen > FORGET_TICKS || now < t.seen) {
                flushHits(t, now, true);
                it.remove();
            }
        }
        for (Iterator<Map.Entry<String, long[]>> it = ledger.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue()[2] != day) {
                it.remove();
            }
        }
        for (Iterator<Map.Entry<String, Long>> it = postNext.entrySet().iterator(); it.hasNext();) {
            long at = it.next().getValue().longValue();
            if (at < now - FORGET_TICKS || at - now > FORGET_TICKS) {
                it.remove();
            }
        }
    }

    /** The cells round a gun: x / z within +-half, y from lo to hi ({dx, dy, dz} offsets - Projectile.home's order). */
    private static int[][] platform(int half, int lo, int hi) {
        List<int[]> out = new ArrayList<int[]>();
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                for (int dy = lo; dy <= hi; dy++) {
                    out.add(new int[] {dx, dy, dz});
                }
            }
        }
        return out.toArray(new int[out.size()][]);
    }
}
