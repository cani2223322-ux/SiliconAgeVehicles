package com.sva.block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.sc.energy.Tier;
import com.sc.machine.UpgradeType;
import com.sva.ConfigSVA;
import com.sva.airdefense.AdUpgradeMath;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.BallisticsMath;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.ZoneMath;
import com.sva.entity.EntityVehicleSVA;
import com.sva.item.ItemAmmoSVA;
import com.sva.net.NetADSVA;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.block.Block;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Air-defence wave (mock-up veh_ad_3_launchers): a launcher - block meta GUN (the AA gun, MV, 32 EU/t, «Снаряды ПВО»)
 * or MISSILE (the missile system, EV, 128 EU/t, «Зенитная ракета» - one per tube, 4 tubes). Every 5 ticks it looks at
 * its picture - the command post's (the same owner's within BallisticsMath.LINK) or, without one, its own eyes
 * (BallisticsMath.EYES, the owner's zones) - picks the targets its rule of engagement wants (fire channels: the gun one,
 * the missile system two), runs the staged warning on each (RoeMath.stage) and, once at «Огонь», asks the server's
 * checks (RoeMath.fireBlock) before every burst / launch: only OK fires; warnings still go where warnsAllowed says.
 * The missile system launches only at a target a tracking radar holds (or within its own eyes). Launches and the first
 * burst of an engagement go to the command post's log and logs/airdefense.log.
 * Upgrades wave: overclockers shorten the gun's burst interval / the missile system's reload (x0.7 each, never under
 * AdUpgradeMath.MIN_BURST / MIN_RELOAD), economizers cheapen the upkeep and every shot, a puller takes rounds from the
 * inventories beside the structure (a chest by any of its blocks) every AdUpgradeMath.PULL_INTERVAL ticks.
 * Audit 2026-10-22 (the friend / foe trust model): its post is the one it is LINKED to (AirDefenseSVA.linkedPost: chosen on
 * placement, changed on its screen - bug 19), and only when that post is powered. Without one it no longer shoots at 7700,
 * today's code or a friend (bug 2): it interrogates what its own eyes see and classes it with the owner's posts' trust data
 * (AirDefenseSVA.trust: the day code, own codes, the white list, the trusted airports, «свой на 10 минут», «только вести»,
 * the 7600 decisions) and gives 7600 the documented 2 minutes in all (AirDefenseDataSVA's 7600 memory - a code change does
 * not restart it). «Только вести» on its post is a hold-fire here: no warnings, no fire.
 * <p>
 * Audit 2026-10-22 (combat effectiveness): the gun leads a radar's mark by its age and takes the target's live position
 * when its own eyes have it (bug 3); a burst needs a clear line from the gun to where it aims, the shells leave through the
 * launcher's own structure and stop at the first block (bug 4); a target at «Огонь» is fired at between the 5-tick rounds
 * as soon as the (overclocked) interval allows; warnings given to an empty cockpit start over when somebody boards; each
 * launcher's warnings reach the pilot on their own (not hushed by another launcher's); the launch log says who ordered the
 * target and the gun's hits (summed up per target).
 */
public class TileEntityLauncherSVA extends TileEntityAirDefenseSVA implements IInventory {

    public static final int GUN = 0, MISSILE = 1, KINDS = 2, SLOTS = 4;
    public static final String[] KEYS = {"gun", "missile"};
    public static final int[] UPKEEP = {32, 128};

    private final ItemStack[] inv = new ItemStack[SLOTS];
    private int roe = RoeMath.VIOLATORS, waitSec = 10, radius;
    private boolean msg = true, warnShot = true;
    // not saved
    private final Map<Integer, long[]> eng = new LinkedHashMap<Integer, long[]>();
    /** Audit 2026-10-22, world tests: the last round's picture - id -> {class, held (1 / 0), replied (1 / 0)}. */
    private final Map<Integer, int[]> lastPic = new LinkedHashMap<Integer, int[]>();
    /**
     * Audit 2026-10-22: the targets at «Огонь» with nothing forbidding fire after the last round (fired at between the rounds
     * too), the last round's post and rule; the gun's hits per target not yet in the log: id -> {shells, hull, first, last}.
     */
    private final Map<Integer, AirDefenseSVA.Contact> ready = new LinkedHashMap<Integer, AirDefenseSVA.Contact>();
    private TileEntityCommandPostSVA kpRound;
    private int roeRound;
    private final Map<Integer, double[]> gunHits = new LinkedHashMap<Integer, double[]>();
    /** World tests: the bursts' aim - {aim x, y, z, flight ticks, live x, y, z, vx, vy, vz, mark age, own sight 1 / 0, tick}. */
    private final List<double[]> aimLog = new ArrayList<double[]>();
    private int hitsTotal;
    private long nextShot;
    private int timer;
    private boolean tierSet;
    private final Random rnd = new Random();

    public TileEntityLauncherSVA() {
        super(Tier.MV);
    }

    public int kind() {
        int m = worldObj == null ? 0 : getBlockMetadata();
        return m == MISSILE ? MISSILE : GUN;
    }

    @Override
    public int baseUpkeep() {
        return UPKEEP[kind()];
    }

    @Override
    public int upgradeBlock() {
        return AdUpgradeMath.B_LAUNCHER;
    }

    /** The gun's burst period / the missile system's reload before the upgrades (ticks). */
    public int baseFireInterval() {
        return kind() == MISSILE ? BallisticsMath.MISSILE_RELOAD : BallisticsMath.GUN_PERIOD;
    }

    @Override
    protected int ocBaseInterval() {
        return baseFireInterval();
    }

    @Override
    protected int ocFloor() {
        return kind() == MISSILE ? AdUpgradeMath.MIN_RELOAD : AdUpgradeMath.MIN_BURST;
    }

    /** Ticks between bursts / launches with the overclockers. */
    public int fireInterval() {
        return AdUpgradeMath.interval(baseFireInterval(), upgradeCount(UpgradeType.OVERCLOCKER), ocFloor());
    }

    /** EU of one burst / launch with the economizers. */
    public int shotCost() {
        return shotEu(kind() == MISSILE ? BallisticsMath.MISSILE_EU : BallisticsMath.GUN_EU_BURST);
    }

    @Override
    public int nodeKind() {
        return AirDefenseDataSVA.N_LAUNCHER;
    }

    @Override
    public String nameKey() {
        return "sva.ad.launcher." + KEYS[kind()];
    }

    public int roe() {
        return roe;
    }

    public double maxRange() {
        return kind() == MISSILE ? BallisticsMath.MISSILE_RANGE : BallisticsMath.GUN_RANGE;
    }

    /** The fire radius: the operator's (16 .. max range), 0 - the full range. */
    public double range() {
        return radius <= 0 ? maxRange() : Math.min(radius, maxRange());
    }

    /**
     * The model (multiblock wave): power; lock - engaging somebody; empty - no ammunition (the red lamp of mock-up *3
     * «Пустые ПУ светят красной лампой»); alarm - an engagement at the warning shot / fire stage or the command post's alarm.
     * The mount turns to the engagement furthest along; the missile system shows its rounds on the rails.
     */
    @Override
    protected int stateBits() {
        int s = super.stateBits();
        int top = -1;
        for (long[] st : eng.values()) {
            top = Math.max(top, (int) st[1]);
        }
        if (powered && top >= RoeMath.ST_MESSAGE) {
            s |= ST_LOCK;
        }
        if (ammo() == 0) {
            s |= ST_EMPTY;
        }
        TileEntityCommandPostSVA kp = AirDefenseSVA.INSTANCE.linkedPost(this);
        if (top >= RoeMath.ST_WARN || kp != null && kp.isAlarm()) {
            s |= ST_ALARM;
        }
        return s;
    }

    @Override
    protected int targetId() {
        int best = -1, top = -1;
        for (Map.Entry<Integer, long[]> en : eng.entrySet()) {
            if (en.getValue()[1] > top) {
                top = (int) en.getValue()[1];
                best = en.getKey().intValue();
            }
        }
        return powered ? best : -1;
    }

    @Override
    protected int ammoShown() {
        return kind() == MISSILE ? Math.min(com.sva.airdefense.AdShapeMath.TUBES, ammo()) : Math.min(1, ammo());
    }

    /** Wave A: the model's tubes refill in the real reload (the overclockers in). */
    @Override
    protected int reloadShown() {
        return kind() == MISSILE ? fireInterval() : super.reloadShown();
    }

    /** Engaged target ids (world tests). */
    public Map<Integer, long[]> engagements() {
        return eng;
    }

    /** World tests (audit 2026-10-22): the last decision round's classes (id -> {class, held, replied}). */
    public Map<Integer, int[]> lastPicture() {
        return lastPic;
    }

    /** World tests (audit 2026-10-22, bug 3): the bursts' aim records (the newest last, 64 kept). */
    public List<double[]> aimLog() {
        return aimLog;
    }

    /** World tests: the shells / missiles of this launcher that hit since it was loaded. */
    public int hitsTotal() {
        return hitsTotal;
    }

    // ------------------------------------------------------------------ the work

    @Override
    protected void serverTick() {
        if (!tierSet) {
            tierSet = true;
            setTier(kind() == MISSILE ? Tier.EV : Tier.MV);
        }
        if (worldObj.getTotalWorldTime() % AdUpgradeMath.PULL_INTERVAL == 0 && upgradeCount(UpgradeType.PULLER) > 0) {
            pull();
        }
        long now = worldObj.getTotalWorldTime();
        if (++timer % 5 != 0) {
            if (powered) {
                fireBetween(now);                                   // audit 2026-10-22: the overclockers' interval, not the round's
            }
            return;
        }
        if (timer % 10 == 0) {
            sendScreens();
        }
        logHits(now, false);
        if (!powered) {
            eng.clear();
            ready.clear();
            return;
        }
        engage(now);
    }

    /** One decision round (the world test may call it). */
    public void engage(long now) {
        TileEntityCommandPostSVA kp = AirDefenseSVA.INSTANCE.workingPost(this);
        final double ax = xCoord + 0.5, ay = yCoord + 1.5, az = zCoord + 0.5;
        List<AirDefenseSVA.Contact> pic;
        if (kp != null) {
            pic = new ArrayList<AirDefenseSVA.Contact>(kp.tracks().values());
            for (AirDefenseSVA.Contact c : pic) {
                c.held = kp.heldFire(c.id);
            }
        } else {
            pic = AirDefenseSVA.INSTANCE.eyes(worldObj, ax, ay, az, Math.min(range(), BallisticsMath.EYES));
            classifyOwn(pic, now);
        }
        lastPic.clear();
        for (AirDefenseSVA.Contact c : pic) {
            lastPic.put(Integer.valueOf(c.id), new int[] {c.cls, c.held ? 1 : 0, c.replied ? 1 : 0});
        }
        int roeNow = kp != null ? roe : RoeMath.roeWithoutKp(roe);
        kpRound = kp;
        roeRound = roeNow;
        ready.clear();
        boolean warnOnly = !ConfigSVA.adFires() && !debugFireMode;
        boolean pvpOff = ConfigSVA.adRequirePvp && !pvp();
        ChunkCoordinates sp = worldObj.getSpawnPoint();
        boolean nearSpawn = sp != null && Math.hypot(xCoord - sp.posX, zCoord - sp.posZ) < ConfigSVA.adMinSpawnDistance;
        final Map<Integer, Integer> want = new LinkedHashMap<Integer, Integer>();
        final Map<Integer, AirDefenseSVA.Contact> byId = new LinkedHashMap<Integer, AirDefenseSVA.Contact>();
        for (AirDefenseSVA.Contact c : pic) {
            if (c.v == null || c.v.isDead || c.v.isWrecked() || BallisticsMath.dist(ax, ay, az, c.x, c.y, c.z) > range() || c.held) {
                continue;                                           // audit 2026-10-22: «только вести» - nothing at all
            }
            boolean assigned = kp != null && kp.assignedTo(c.id, this);
            boolean wants = RoeMath.roeWants(roeNow, c.cls, assigned, kp != null && kp.isAuto());
            boolean ownBlocked = c.rel == 1 || c.rel == 2 && !ConfigSVA.adFireOnFriends;
            boolean special = c.cls == RoeMath.EMERGENCY || c.cls == RoeMath.FRIEND || c.cls == RoeMath.NO_RADIO;
            boolean prot = !ConfigSVA.adFireInProtectedRegions && com.sc.tileentity.TileEntityFieldGeneratorSC.privateRefusingName(worldObj, ownerName,
                    c.v.posX, c.v.posY, c.v.posZ) != null;
            int block = RoeMath.fireBlock(ConfigSVA.adEnabled, nearSpawn, prot, ownBlocked, pvpOff, !c.manned && !ConfigSVA.adFireOnUnmanned,
                    special, warnOnly, wants);
            if (wants && RoeMath.warnsAllowed(block)) {
                want.put(Integer.valueOf(c.id), Integer.valueOf(block));
                byId.put(Integer.valueOf(c.id), c);
            }
        }
        for (Iterator<Integer> it = eng.keySet().iterator(); it.hasNext();) {
            if (!want.containsKey(it.next())) {
                it.remove();                                        // left the zone / own code / 7700 / gone: starts over
            }
        }
        List<Integer> order = new ArrayList<Integer>(want.keySet());
        final TileEntityCommandPostSVA k = kp;
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                int ea = eng.containsKey(a) ? 0 : 1, eb = eng.containsKey(b) ? 0 : 1;
                if (ea != eb) {
                    return ea - eb;
                }
                int sa = k != null && k.assignedTo(a.intValue(), TileEntityLauncherSVA.this) ? 0 : 1;
                int sb = k != null && k.assignedTo(b.intValue(), TileEntityLauncherSVA.this) ? 0 : 1;
                if (sa != sb) {
                    return sa - sb;
                }
                AirDefenseSVA.Contact ca = byId.get(a), cb = byId.get(b);
                return Double.compare(BallisticsMath.dist(ax, ay, az, ca.x, ca.y, ca.z), BallisticsMath.dist(ax, ay, az, cb.x, cb.y, cb.z));
            }
        });
        int channels = kind() == MISSILE ? BallisticsMath.MISSILE_CHANNELS : 1;
        for (int i = 0; i < order.size() && i < channels; i++) {
            Integer id = order.get(i);
            if (!eng.containsKey(id)) {
                // {began, stage, first fire logged, anyone aboard at a round so far (audit 2026-10-22: who heard the warnings)}
                eng.put(id, new long[] {now, RoeMath.ST_NONE, 0, byId.get(id).manned ? 1 : 0});
            }
        }
        for (Iterator<Map.Entry<Integer, long[]>> it = eng.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, long[]> e = it.next();
            if (!order.subList(0, Math.min(order.size(), channels)).contains(e.getKey())) {
                it.remove();
            }
        }
        int wait = RoeMath.waitTicks(waitSec, ConfigSVA.adMinWarningSeconds);
        for (Map.Entry<Integer, long[]> e : eng.entrySet()) {
            AirDefenseSVA.Contact c = byId.get(e.getKey());
            long[] st = e.getValue();
            if (RoeMath.restartWarnings(st[3] != 0, c.manned, (int) st[1])) {
                st[0] = now;                                        // the warnings went to an empty cockpit: again, to the pilot
                st[1] = RoeMath.ST_NONE;
                st[2] = 0;
                for (String said : new String[] {"warn|", "lock|", "warnShot|"}) {
                    AirDefenseSVA.INSTANCE.unsay(c.v, said + key());
                }
            }
            st[3] = RoeMath.heard(st[3] != 0, c.manned) ? 1 : 0;      // audit review: once heard, for the whole engagement
            long elapsed = now - st[0];
            int stage = RoeMath.stage(elapsed, msg, warnShot, wait);
            int toFire = RoeMath.secondsToFire(elapsed, msg, warnShot, wait);
            int block = want.get(e.getKey()).intValue();
            step(c, st, stage, toFire, block, kp, now, ax, ay, az);
            if (stage == RoeMath.ST_FIRE && block == RoeMath.OK) {
                ready.put(e.getKey(), c);
            }
            if (kp != null) {
                kp.reportEngaged(c.id, this, stage, toFire);
            }
        }
    }

    /**
     * Audit 2026-10-22: between the 5-tick rounds a target already at «Огонь» (nothing forbidding it at the last round) gets
     * its next burst / launch as soon as the interval allows - before, the round rounded every interval up to 5 ticks and
     * the overclockers' speed-up below 10 ticks was paid for and lost (the gun's 7 ticks were 10, 3 were 5).
     */
    private void fireBetween(long now) {
        if (ready.isEmpty() || now < nextShot) {
            return;
        }
        double ax = xCoord + 0.5, ay = yCoord + 1.5, az = zCoord + 0.5;
        for (Map.Entry<Integer, AirDefenseSVA.Contact> e : ready.entrySet()) {
            AirDefenseSVA.Contact c = e.getValue();
            if (holdsNow(c)) {
                continue;                                           // audit review: «только вести» pressed since the round
            }
            long[] st = eng.get(e.getKey());
            if (st == null || c.v == null || c.v.isDead || c.v.isWrecked() || BallisticsMath.dist(ax, ay, az, c.v.posX, c.v.posY, c.v.posZ) > range()) {
                continue;
            }
            if (fire(c, st, kpRound, now, ax, ay, az)) {
                return;
            }
        }
    }

    /**
     * Audit review 2026-10-22: a target of the last round held now - «только вести» pressed on the post since (the post's track
     * object, its own flag set at once), or the post gone off / away. Between the 5-tick rounds an overclocked gun or a
     * missile launcher fired up to 4 ticks after «Только вести» - a missile once launched is not called back.
     */
    private boolean holdsNow(AirDefenseSVA.Contact c) {
        if (c.held) {
            return true;
        }
        TileEntityCommandPostSVA k = kpRound;
        return k != null && (k.isInvalid() || !k.isPowered() || k.heldFire(c.id));
    }

    /**
     * Audit 2026-10-22 (bug 2): no working post - the targets of its own eyes interrogated and classed with what the owner's
     * posts trust (the linked post's data, or every post of the owner's): 7700 / today's code / an own code / the white list /
     * a friendly airport's code its tower gave it / «свой на 10 минут» are never fired at, 7600 gets its 2 minutes in all.
     */
    private void classifyOwn(List<AirDefenseSVA.Contact> pic, long now) {
        AirDefenseDataSVA d = AirDefenseDataSVA.get(worldObj);
        List<ZoneMath.Zone> zones = d == null ? new ArrayList<ZoneMath.Zone>() : d.ofOwner(ownerName, worldObj.provider.dimensionId);
        List<ZoneMath.Zone> apts = new ArrayList<ZoneMath.Zone>();
        if (d != null && ConfigSVA.adAirportFriends) {
            for (ZoneMath.Zone z : d.all()) {
                if (z.type == ZoneMath.AERODROME && z.dim == worldObj.provider.dimensionId) {
                    apts.add(z);
                }
            }
        }
        AirDefenseSVA.Trust tr = AirDefenseSVA.INSTANCE.trust(worldObj, ownerName, kpLink());
        for (final AirDefenseSVA.Contact c : pic) {
            AirDefenseSVA.INSTANCE.interrogate(c, c.v, now);
            c.rel = relation(c.owner);
            boolean ownFriend = c.rel == 1 || c.rel == 2 && !ConfigSVA.adFireOnFriends;
            boolean ownCode = c.replied && (c.code == tr.dayCode && tr.dayCode >= 0 || tr.ownCode(c.code));
            ZoneMath.Zone viol = ZoneMath.violated(zones, c.x, c.y, c.z, c.course(), true);
            boolean handed = false;
            for (AirDefenseDataSVA.Handoff h : AirDefenseSVA.INSTANCE.handoffs(worldObj, c.vid, now)) {
                handed |= relation(h.owner) != 0 || com.sva.airdefense.AirportCodeMath.trustedTower(tr.trusted, h.icao, h.owner);
            }
            // audit review 2026-10-22: the owner's posts' aerodrome ranges are an airport's code (public: no friend over a
            // violation or a handoff), never his towers' (those only when the tower gave the code - friendOf)
            boolean apt = c.replied && !ownCode && viol == null && !handed && (com.sva.airdefense.AirportCodeMath.postRange(zones, c.code, c.x, c.y, c.z,
                    true) != null || com.sva.airdefense.AirportCodeMath.friendOf(apts, c.code, c.x, c.y, c.z,
                    true, ownerName, access, tr.trusted, new com.sva.airdefense.AirportCodeMath.Issued() {
                        @Override
                        public boolean to(ZoneMath.Zone a, int code) {
                            return AtcServerSVA.INSTANCE.issued(com.sva.airdefense.AirportCodeMath.towerKey(a), code, c.id, c.vid);
                        }
                    }) != null);
            boolean white = c.replied && com.sva.airdefense.XpdrMath.givesBoard(c.mode) && tr.whitelisted(c.board, c.owner);
            boolean nr = c.replied && c.code == com.sva.airdefense.XpdrMath.NO_RADIO;
            com.sva.airdefense.NoRadioMath.Entry fe = d == null ? null : d.freeNoRadio(c.vid, nr, now);
            boolean grace = nr && !tr.hostile(c.vid) && com.sva.airdefense.NoRadioMath.grace(fe, now, com.sva.airdefense.XpdrMath.NO_RADIO_GRACE);
            c.cls = RoeMath.classify(ownFriend, c.replied, c.code, ownCode, apt, white, tr.granted(c.vid, nr, now), grace, viol != null || handed);
            c.zone = viol == null ? "" : viol.name;
            c.held = tr.held(c.vid, nr, now);
        }
    }

    private static boolean pvp() {
        MinecraftServer s = MinecraftServer.getServer();
        return s != null && s.isPVPEnabled();
    }

    private String myName(TileEntityCommandPostSVA kp) {
        int n = kp == null ? 0 : kp.launcherNo(this);
        return n > 0 ? String.valueOf(n) : "-";
    }

    /** One engagement's stage: what the pilot hears on entering each stage, the lock's paint, the fire. */
    private void step(AirDefenseSVA.Contact c, long[] st, int stage, int toFire, int block, TileEntityCommandPostSVA kp, long now,
            double ax, double ay, double az) {
        EntityVehicleSVA v = c.v;
        String src = kp != null ? kp.name() : "-";
        String trk = kp != null ? String.format("%02d", Integer.valueOf(c.no)) : "-";
        int was = (int) st[1];
        if (stage > was) {
            st[1] = stage;
            if (stage >= RoeMath.ST_MESSAGE && was < RoeMath.ST_MESSAGE && msg) {
                ZoneMath.Zone z = zoneOf(kp, c);
                // audit 2026-10-22: hushed per launcher - a second launcher's warning and countdown reach the pilot too
                if (z != null) {
                    AirDefenseSVA.INSTANCE.tellPilot(v, "warn|" + key(), 200, 0xF05050, "sva.ad.msg.warnZone", src, z.name,
                            String.format("%03d", Integer.valueOf(ZoneMath.exitCourse(z, c.x, c.z))));
                } else {                                            // 7.3: no zone (an IFF handoff) - no «покиньте зону ., курс 000»
                    AirDefenseSVA.INSTANCE.tellPilot(v, "warn|" + key(), 200, 0xF05050, "sva.ad.msg.warnForeign", src);
                }
            }
            if (stage >= RoeMath.ST_LOCK && was < RoeMath.ST_LOCK) {
                AirDefenseSVA.INSTANCE.tellPilot(v, "lock|" + key(), 200, 0xF05050, "sva.ad.msg.lock", src, String.valueOf(toFire));
                if (kp != null) {
                    kp.log("sva.ad.log.lock", myName(kp), trk);
                }
            }
            if (stage == RoeMath.ST_WARN) {
                warningShot(c, ax, ay, az);
                AirDefenseSVA.INSTANCE.tellPilot(v, "warnShot|" + key(), 200, 0xF05050, "sva.ad.msg.warnShot", src);
            }
        }
        if (stage >= RoeMath.ST_LOCK) {
            // audit 2026-10-22 (the RWR): the launcher's own lock - its own kind, told apart from a tracking radar's on the scope
            AirDefenseSVA.INSTANCE.paint(v, RadarMath.T_LAUNCHER, ax, az, now + 20);
        }
        if (stage == RoeMath.ST_FIRE && block == RoeMath.OK) {
            fire(c, st, kp, now, ax, ay, az);
        }
    }

    /** A burst / a launch at a target at «Огонь» and its lines in the logs. @return it went */
    private boolean fire(AirDefenseSVA.Contact c, long[] st, TileEntityCommandPostSVA kp, long now, double ax, double ay, double az) {
        EntityVehicleSVA v = c.v;
        String trk = kp != null ? String.format("%02d", Integer.valueOf(c.no)) : "-";
        boolean shot = kind() == MISSILE ? launchMissile(c, now, ax, ay, az) : burst(c, now, ax, ay, az);
        if (shot && st[2] == 0) {
            st[2] = 1;
            String line = String.format("%s %s (%d %d %d, %s): %s %s (%s, %s, %s) - %s", kind() == MISSILE ? "SAM" : "AA", myName(kp),
                    Integer.valueOf(xCoord), Integer.valueOf(yCoord), Integer.valueOf(zCoord), ownerName, kind() == MISSILE ? "launch at" : "fire at",
                    trk, v.getOwnerName(), v.getVehicleType().key, c.replied ? com.sva.airdefense.XpdrMath.format(c.code) : "----", order(c, kp));
            AirDefenseSVA.INSTANCE.launchLog(line);
            if (kp != null) {
                kp.log(kind() == MISSILE ? "sva.ad.log.launch" : "sva.ad.log.fire", myName(kp), trk);
            }
        } else if (shot && kind() == MISSILE) {
            AirDefenseSVA.INSTANCE.launchLog(String.format("SAM %s (%d %d %d, %s): launch at %s (%s) - %s", myName(kp), Integer.valueOf(xCoord),
                    Integer.valueOf(yCoord), Integer.valueOf(zCoord), ownerName, trk, v.getOwnerName(), order(c, kp)));
            if (kp != null) {
                kp.log("sva.ad.log.launch", myName(kp), trk);
            }
        }
        return shot;
    }

    /** Audit 2026-10-22: why this target - who assigned it on the post, the post's «Авто», or the launcher's own rule. */
    private String order(AirDefenseSVA.Contact c, TileEntityCommandPostSVA kp) {
        String by = kp == null ? "" : kp.assignedBy(c.id, this);
        if (by.length() > 0) {
            return "assigned by " + by;
        }
        if (kp != null && roeRound == RoeMath.KP_MARKS && kp.isAuto()) {
            return "post " + kp.name() + " auto";
        }
        return (kp == null ? "own eyes, rule " : "rule ") + RoeMath.ROE_KEYS[Math.max(0, Math.min(RoeMath.ROES - 1, roeRound))];
    }

    private ZoneMath.Zone zoneOf(TileEntityCommandPostSVA kp, AirDefenseSVA.Contact c) {
        if (c.zone.length() == 0) {
            return null;
        }
        AirDefenseDataSVA d = AirDefenseDataSVA.get(worldObj);
        List<ZoneMath.Zone> l = kp != null ? kp.zones() : d == null ? new ArrayList<ZoneMath.Zone>() : d.ofOwner(ownerName, worldObj.provider.dimensionId);
        for (ZoneMath.Zone z : l) {
            if (z.name.equals(c.zone)) {
                return z;
            }
        }
        return null;
    }

    /** Audit 2026-10-22 (bug 3): the launcher's own eyes have the target now - within EYES, seen at all, a clear line. */
    private boolean ownSight(EntityVehicleSVA v, double ax, double ay, double az) {
        double[] live = AirDefenseSVA.centre(v);
        return BallisticsMath.dist(ax, ay, az, live[0], live[1], live[2]) <= BallisticsMath.EYES && AirDefenseSVA.radarVisible(v)
                && AirDefenseSVA.losClear(worldObj, ax, ay, az, live[0], live[1], live[2]);
    }

    /**
     * The point to shoot at, led for the projectile's flight by the vehicle's velocity. Audit 2026-10-22 (bug 3): the
     * target's live centre when the launcher's own eyes have it; else the mark (its error in) moved on by the velocity over
     * the mark's age (the position's and the altitude's apart - BallisticsMath.led); no altitude known - the sight's.
     * @param own the launcher's own eyes have it (ownSight)
     */
    private double[] aim(AirDefenseSVA.Contact c, double ax, double ay, double az, double speed, int maxTicks, long now, boolean own) {
        double[] vel = AirDefenseSVA.INSTANCE.velocity(c.v);
        double tx, ty, tz;
        if (own) {
            double[] live = AirDefenseSVA.centre(c.v);
            tx = live[0];
            ty = live[1];
            tz = live[2];
        } else {
            long posAge = now - (c.posAt >= 0 ? c.posAt : c.seen);
            double[] p = BallisticsMath.led(c.x, 0, c.z, vel[0], 0, vel[2], posAge);
            tx = p[0];
            tz = p[2];
            if (c.altKnown) {
                ty = BallisticsMath.led(0, c.y, 0, 0, vel[1], 0, now - (c.altAt >= 0 ? c.altAt : c.seen))[1];
            } else {
                ty = c.v.posY + c.v.height / 2 + RadarMath.jitter(c.id, now / 20, 1, RadarMath.ERROR[RadarMath.SURVEY]);
            }
        }
        return BallisticsMath.gunAim(ax, ay, az, tx, ty, tz, vel[0], vel[1], vel[2], speed, maxTicks);  // the shells' first step this tick
    }

    private boolean burst(AirDefenseSVA.Contact c, long now, double ax, double ay, double az) {
        if (now < nextShot || getEnergyStored() < shotCost()) {
            return false;
        }
        boolean own = ownSight(c.v, ax, ay, az);
        double[] p = aim(c, ax, ay, az, BallisticsMath.GUN_SPEED, BallisticsMath.SHELL_LIFE, now, own);
        // audit 2026-10-22 (bug 4): a clear line from the gun to where it aims (a post's track it cannot see past a wall - no burst)
        if (p == null || !AirDefenseSVA.losClear(worldObj, ax, ay, az, p[0], p[1], p[2]) || !take()) {
            return false;
        }
        nextShot = now + fireInterval();
        removeEnergy(shotCost());
        double[] live = AirDefenseSVA.centre(c.v), vel = AirDefenseSVA.INSTANCE.velocity(c.v);
        aimLog.add(new double[] {p[0], p[1], p[2], p[3], live[0], live[1], live[2], vel[0], vel[1], vel[2],
            now - (c.posAt >= 0 ? c.posAt : c.seen), own ? 1 : 0, now});
        while (aimLog.size() > 64) {
            aimLog.remove(0);
        }
        double[] d = BallisticsMath.dir(ax, ay, az, p[0], p[1], p[2]);
        double spread = own || c.precise() || (c.kinds & 1 << RadarMath.HEIGHT) != 0 ? BallisticsMath.GUN_SPREAD : BallisticsMath.GUN_SPREAD_SURVEY;
        for (int i = 0; i < BallisticsMath.GUN_BURST; i++) {
            double[] s = BallisticsMath.spread(d, spread, rnd);
            AirDefenseSVA.Projectile pr = new AirDefenseSVA.Projectile();
            pr.x = ax;
            pr.y = ay;
            pr.z = az;
            pr.vx = s[0] * BallisticsMath.GUN_SPEED;
            pr.vy = s[1] * BallisticsMath.GUN_SPEED;
            pr.vz = s[2] * BallisticsMath.GUN_SPEED;
            pr.life = BallisticsMath.SHELL_LIFE;
            pr.target = c.id;
            pr.dim = worldObj.provider.dimensionId;
            pr.damage = BallisticsMath.SHELL_DAMAGE;
            pr.launcher = key();
            pr.from(this);                                          // audit 2026-10-22: out through its own mount
            AirDefenseSVA.INSTANCE.fire(pr);
        }
        worldObj.playSoundEffect(ax, ay, az, "fireworks.blast_far", 3F, 0.6F);
        shotEvent(EV_BURST, 0);                                     // the model's recoil + muzzle flash
        return true;
    }

    private boolean launchMissile(AirDefenseSVA.Contact c, long now, double ax, double ay, double az) {
        boolean guided = (c.kinds & 1 << RadarMath.TRACK) != 0
                || BallisticsMath.dist(ax, ay, az, c.v.posX, c.v.posY, c.v.posZ) <= BallisticsMath.EYES && (c.kinds & 1 << AirDefenseSVA.EYES_BIT) != 0;
        if (!guided || now < nextShot || getEnergyStored() < shotCost()
                || AirDefenseSVA.INSTANCE.missilesOf(key()) >= BallisticsMath.MISSILE_CHANNELS) {
            return false;
        }
        for (AirDefenseSVA.Projectile p : AirDefenseSVA.INSTANCE.shots()) {
            if (p.missile && p.target == c.id && p.launcher.equals(key())) {
                return false;                                       // one missile at a target at a time
            }
        }
        if (!take()) {
            return false;
        }
        nextShot = now + fireInterval();
        removeEnergy(shotCost());
        double[] d = BallisticsMath.dir(ax, ay, az, c.x, c.y + 6, c.z);         // up out of the tube first
        AirDefenseSVA.Projectile pr = new AirDefenseSVA.Projectile();
        pr.missile = true;
        pr.x = ax;
        pr.y = ay + 0.5;
        pr.z = az;
        pr.vx = d[0] * BallisticsMath.MISSILE_SPEED;
        pr.vy = Math.max(0.5, d[1]) * BallisticsMath.MISSILE_SPEED;
        pr.vz = d[2] * BallisticsMath.MISSILE_SPEED;
        pr.life = BallisticsMath.MISSILE_LIFE;
        pr.target = c.id;
        pr.dim = worldObj.provider.dimensionId;
        pr.damage = BallisticsMath.MISSILE_DAMAGE;
        pr.launcher = key();
        pr.from(this);
        AirDefenseSVA.INSTANCE.fire(pr);
        worldObj.playSoundEffect(ax, ay, az, "fireworks.launch", 4F, 0.5F);
        shotEvent(EV_LAUNCH, 0);                                    // the model: a tube empties until the reload
        return true;
    }

    /** Stage 3: tracers across the nose - harmless, 15 blocks ahead of the target. */
    private void warningShot(AirDefenseSVA.Contact c, double ax, double ay, double az) {
        double yaw = Math.toRadians(c.v.rotationYaw);
        double fx = c.v.posX - Math.sin(yaw) * 15, fz = c.v.posZ + Math.cos(yaw) * 15, fy = c.v.posY + 3;
        double[] d = BallisticsMath.dir(ax, ay, az, fx, fy, fz);
        for (int i = 0; i < 4; i++) {
            double[] s = BallisticsMath.spread(d, 2.0, rnd);
            AirDefenseSVA.Projectile pr = new AirDefenseSVA.Projectile();
            pr.harmless = true;
            pr.x = ax;
            pr.y = ay;
            pr.z = az;
            pr.vx = s[0] * BallisticsMath.GUN_SPEED;
            pr.vy = s[1] * BallisticsMath.GUN_SPEED;
            pr.vz = s[2] * BallisticsMath.GUN_SPEED;
            pr.life = (int) Math.min(BallisticsMath.SHELL_LIFE * 2, BallisticsMath.dist(ax, ay, az, fx, fy, fz) / BallisticsMath.GUN_SPEED + 10);
            pr.target = -1;
            pr.dim = worldObj.provider.dimensionId;
            pr.launcher = key();
            pr.from(this);
            AirDefenseSVA.INSTANCE.fire(pr);
        }
        worldObj.playSoundEffect(ax, ay, az, "fireworks.blast_far", 3F, 0.8F);
        shotEvent(EV_BURST, 1);
    }

    /**
     * A hit (AirDefenseSVA): a missile's goes to the logs at once; audit 2026-10-22 - the gun's are summed up per target and
     * written when its hits pause for a second or every 5 s (logHits: a burst is three shells every few ticks).
     */
    public void onHit(EntityVehicleSVA target, boolean missile, float damage) {
        hitsTotal++;
        if (!missile) {
            long now = worldObj.getTotalWorldTime();
            double[] h = gunHits.get(Integer.valueOf(target.getEntityId()));
            if (h == null) {
                h = new double[] {0, 0, now, now};
                gunHits.put(Integer.valueOf(target.getEntityId()), h);
            }
            h[0]++;
            h[1] += damage;
            h[3] = now;
            return;
        }
        TileEntityCommandPostSVA kp = AirDefenseSVA.INSTANCE.workingPost(this);
        String trk = "-";
        if (kp != null) {
            AirDefenseSVA.Contact c = kp.tracks().get(Integer.valueOf(target.getEntityId()));
            trk = c == null ? "-" : String.format("%02d", Integer.valueOf(c.no));
            kp.log("sva.ad.log.hit", trk, String.valueOf(Math.round(damage)));
        }
        AirDefenseSVA.INSTANCE.launchLog(String.format("SAM %s (%d %d %d): hit %s (%s, -%d hull)", myName(kp), Integer.valueOf(xCoord), Integer.valueOf(yCoord),
                Integer.valueOf(zCoord), trk, target.getOwnerName(), Integer.valueOf(Math.round(damage))));
    }

    /**
     * Audit 2026-10-22: the gun's hits into the launch log and the post's log - per target, once its hits paused for 20 ticks
     * or 5 s after the first (all of them: `all`).
     */
    public void logHits(long now, boolean all) {
        if (gunHits.isEmpty()) {
            return;
        }
        TileEntityCommandPostSVA kp = null;
        boolean kpLooked = false;
        for (Iterator<Map.Entry<Integer, double[]>> it = gunHits.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, double[]> e = it.next();
            double[] h = e.getValue();
            if (!all && now - (long) h[3] < 20 && now - (long) h[2] < 100 && now >= (long) h[2]) {
                continue;
            }
            it.remove();
            if (!kpLooked) {
                kp = AirDefenseSVA.INSTANCE.workingPost(this);
                kpLooked = true;
            }
            net.minecraft.entity.Entity te = worldObj.getEntityByID(e.getKey().intValue());
            String who = te instanceof EntityVehicleSVA ? ((EntityVehicleSVA) te).getOwnerName() : "?";
            AirDefenseSVA.Contact c = kp == null ? null : kp.tracks().get(e.getKey());
            String trk = c == null ? "-" : String.format("%02d", Integer.valueOf(c.no));
            AirDefenseSVA.INSTANCE.launchLog(String.format("AA %s (%d %d %d, %s): hits on %s (%s): %d shells, -%d hull", myName(kp), Integer.valueOf(xCoord),
                    Integer.valueOf(yCoord), Integer.valueOf(zCoord), ownerName, trk, who, Integer.valueOf((int) h[0]), Long.valueOf(Math.round(h[1]))));
            if (kp != null) {
                kp.log("sva.ad.log.hit", trk, String.valueOf(Math.round(h[1])));
            }
        }
    }

    // ------------------------------------------------------------------ ammunition

    public boolean isAmmo(ItemStack s) {
        return s != null && s.getItem() instanceof ItemAmmoSVA && s.getItemDamage() == (kind() == MISSILE ? ItemAmmoSVA.MISSILE : ItemAmmoSVA.SHELLS);
    }

    /** Takes one round (a shell item a burst, a missile a launch). */
    private boolean take() {
        for (int i = 0; i < SLOTS; i++) {
            if (isAmmo(inv[i])) {
                decrStackSize(i, 1);
                return true;
            }
        }
        return false;
    }

    public int ammo() {
        int n = 0;
        for (ItemStack s : inv) {
            if (isAmmo(s)) {
                n += s.stackSize;
            }
        }
        return n;
    }

    public int ammoMax() {
        return SLOTS * getInventoryStackLimit();
    }

    /**
     * The puller (the core machines' module): up to AdUpgradeMath.PULL_ITEMS rounds from the inventories touching any block
     * of the structure (not other air-defence blocks), through the faces they let out of (ISidedInventory). @return taken
     */
    public int pull() {
        if (ammo() >= ammoMax()) {
            return 0;
        }
        int budget = AdUpgradeMath.PULL_ITEMS, taken = 0;
        int[][] c = cells();
        java.util.Set<Long> seen = new java.util.HashSet<Long>();
        for (int i = 0; i < c.length && budget > 0; i++) {
            for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
                int x = xCoord + c[i][0] + d.offsetX, y = yCoord + c[i][1] + d.offsetY, z = zCoord + c[i][2] + d.offsetZ;
                if (y < 0 || y > 255 || !worldObj.blockExists(x, y, z) || !seen.add(Long.valueOf(((long) x & 0x3FFFFFF) << 34 | ((long) z & 0x3FFFFFF) << 8 | y))) {
                    continue;
                }
                Block b = worldObj.getBlock(x, y, z);
                if (b instanceof BlockAirDefenseSVA || b instanceof BlockAdPartSVA) {
                    continue;
                }
                TileEntity te = worldObj.getTileEntity(x, y, z);
                if (!(te instanceof IInventory)) {
                    continue;
                }
                int n = pullFrom((IInventory) te, d.getOpposite().ordinal(), budget);
                budget -= n;
                taken += n;
                if (budget <= 0) {
                    break;
                }
            }
        }
        return taken;
    }

    private int pullFrom(IInventory from, int side, int budget) {
        int[] slots;
        if (from instanceof ISidedInventory) {
            slots = ((ISidedInventory) from).getAccessibleSlotsFromSide(side);
        } else {
            slots = new int[from.getSizeInventory()];
            for (int i = 0; i < slots.length; i++) {
                slots[i] = i;
            }
        }
        int taken = 0;
        for (int i = 0; slots != null && i < slots.length && taken < budget; i++) {
            ItemStack s = from.getStackInSlot(slots[i]);
            if (!isAmmo(s) || from instanceof ISidedInventory && !((ISidedInventory) from).canExtractItem(slots[i], s, side)) {
                continue;
            }
            ItemStack move = s.copy();
            move.stackSize = Math.min(s.stackSize, budget - taken);
            int n = insert(move);
            if (n <= 0) {
                break;                                              // full
            }
            from.decrStackSize(slots[i], n);
            from.markDirty();
            taken += n;
        }
        return taken;
    }

    /** Shift+RMB with ammunition in hand: as much as fits goes in. @return taken */
    public int insert(ItemStack s) {
        if (!isAmmo(s)) {
            return 0;
        }
        int before = s.stackSize;
        for (int i = 0; i < SLOTS && s.stackSize > 0; i++) {
            if (inv[i] == null) {
                int n = Math.min(s.stackSize, getInventoryStackLimit());
                inv[i] = s.splitStack(n);
            } else if (inv[i].isItemEqual(s) && inv[i].stackSize < getInventoryStackLimit()) {
                int n = Math.min(s.stackSize, getInventoryStackLimit() - inv[i].stackSize);
                inv[i].stackSize += n;
                s.stackSize -= n;
            }
        }
        markDirty();
        return before - s.stackSize;
    }

    // ------------------------------------------------------------------ the screen

    public void applySettings(NBTTagCompound d) {
        roe = Math.max(0, Math.min(RoeMath.ROES - 1, d.getInteger("Roe")));
        msg = d.getBoolean("Msg");
        warnShot = d.getBoolean("Warn");
        waitSec = Math.max(RoeMath.WAIT_MIN, Math.min(RoeMath.WAIT_MAX, d.getInteger("Wait")));
        int r = d.getInteger("Radius");
        radius = r <= 0 ? 0 : Math.max(16, Math.min((int) maxRange(), r));
        eng.clear();
        markDirty();
    }

    /**
     * World tests (audit review 2026-10-22): this launcher fires whatever airdefense.mode the other world tests running at the
     * same time leave behind (their snapshots written back) - a test of what stops its fire must not pass on a mode flip.
     */
    public boolean debugFireMode;

    /** World tests: the target was at «Огонь» with nothing forbidding the fire at the last round (fireBetween's list). */
    public boolean debugReady(int id) {
        return ready.containsKey(Integer.valueOf(id));
    }

    /** World tests (audit review 2026-10-22): fireBetween would let this ready target go now (held since the round). */
    public boolean debugHeldBetween(int id) {
        AirDefenseSVA.Contact c = ready.get(Integer.valueOf(id));
        return c != null && holdsNow(c);
    }

    /** World tests: the settings without the screen. */
    public void debugSettings(int roe, boolean msg, boolean warnShot, int waitSec) {
        this.roe = roe;
        this.msg = msg;
        this.warnShot = warnShot;
        this.waitSec = waitSec;
    }

    public NBTTagCompound snapshot() {
        NBTTagCompound t = new NBTTagCompound();
        t.setIntArray("Pos", new int[] {xCoord, yCoord, zCoord});
        t.setByte("Kind", (byte) kind());
        t.setByte("Roe", (byte) roe);
        t.setBoolean("Msg", msg);
        t.setBoolean("Warn", warnShot);
        t.setInteger("Wait", waitSec);
        t.setInteger("MinWait", ConfigSVA.adMinWarningSeconds);
        t.setInteger("Radius", radius);
        t.setInteger("MaxR", (int) maxRange());
        t.setInteger("Energy", getEnergyStored());
        t.setInteger("EMax", getMaxEnergyStored());
        t.setBoolean("Powered", powered);
        t.setInteger("Ammo", ammo());
        t.setInteger("AmmoMax", ammoMax());
        t.setString("Own", ownerName);
        t.setString("Mode", ConfigSVA.adFires() ? "fire" : ConfigSVA.adEnabled ? "warn" : "off");
        // upgrades wave: the input, the upkeep, the burst / reload interval, a shot's EU
        t.setByte("Tier", (byte) getTier().ordinal());
        t.setByte("In", (byte) inputTier().ordinal());
        t.setBoolean("Any", acceptsAnyVoltage());
        t.setInteger("Use", upkeep());
        t.setInteger("BaseUse", baseUpkeep());
        t.setInteger("Interval", fireInterval());
        t.setInteger("BaseInterval", baseFireInterval());
        t.setInteger("ShotEu", shotCost());
        t.setBoolean("Puller", upgradeCount(UpgradeType.PULLER) > 0);
        t.setBoolean("Upg", ConfigSVA.adUpgrades);
        TileEntityCommandPostSVA kp = AirDefenseSVA.INSTANCE.linkedPost(this);
        t.setString("Kp", kp == null ? "" : kp.name());
        t.setBoolean("KpOn", kp != null && kp.isPowered());
        t.setBoolean("KpLinked", kpLink().length() > 0);
        t.setInteger("No", kp == null ? 0 : kp.launcherNo(this));
        boolean track = false;
        if (kp != null) {
            for (TileEntityRadarSVA r : kp.radars()) {
                track |= r.kind() == RadarMath.TRACK && r.isPowered();
            }
        }
        t.setBoolean("Track", track);
        NBTTagList tl = new NBTTagList();
        for (Map.Entry<Integer, long[]> e : eng.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            AirDefenseSVA.Contact k = kp == null ? null : kp.tracks().get(e.getKey());
            c.setInteger("No", k == null ? 0 : k.no);
            c.setByte("St", (byte) e.getValue()[1]);
            tl.appendTag(c);
        }
        t.setTag("Targets", tl);
        return t;
    }

    private void sendScreens() {
        NBTTagCompound snap = null;
        for (Object o : worldObj.playerEntities) {
            if (o instanceof EntityPlayerMP && ((EntityPlayerMP) o).openContainer instanceof com.sva.inventory.ContainerLauncherSVA
                    && ((com.sva.inventory.ContainerLauncherSVA) ((EntityPlayerMP) o).openContainer).tile == this) {
                if (snap == null) {
                    snap = snapshot();
                }
                NetADSVA.send((EntityPlayerMP) o, NetADSVA.D_LAUNCHER, snap);
            }
        }
    }

    // ------------------------------------------------------------------ IInventory

    @Override
    public int getSizeInventory() {
        return SLOTS;
    }

    @Override
    public ItemStack getStackInSlot(int i) {
        return i >= 0 && i < SLOTS ? inv[i] : null;
    }

    @Override
    public ItemStack decrStackSize(int i, int n) {
        if (i < 0 || i >= SLOTS || inv[i] == null) {
            return null;
        }
        ItemStack out;
        if (inv[i].stackSize <= n) {
            out = inv[i];
            inv[i] = null;
        } else {
            out = inv[i].splitStack(n);
        }
        markDirty();
        return out;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int i) {
        return null;
    }

    @Override
    public void setInventorySlotContents(int i, ItemStack s) {
        if (i >= 0 && i < SLOTS) {
            inv[i] = s;
            if (s != null && s.stackSize > getInventoryStackLimit()) {
                s.stackSize = getInventoryStackLimit();
            }
            markDirty();
        }
    }

    @Override
    public String getInventoryName() {
        return "container.sva.launcher";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    /** A missile per tube; shells by the stack. */
    @Override
    public int getInventoryStackLimit() {
        return worldObj != null && kind() == MISSILE ? 1 : 64;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer p) {
        return worldObj.getTileEntity(xCoord, yCoord, zCoord) == this && p.getDistanceSq(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) <= 64 && mayUse(p);
    }

    @Override
    public void openInventory() {
    }

    @Override
    public void closeInventory() {
    }

    @Override
    public boolean isItemValidForSlot(int i, ItemStack s) {
        return isAmmo(s);
    }

    /** The world test: ammunition straight in. */
    public void debugAmmo(ItemStack s) {
        insert(s);
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public void readFromNBT(NBTTagCompound t) {
        super.readFromNBT(t);
        roe = Math.max(0, Math.min(RoeMath.ROES - 1, t.hasKey("LRoe") ? t.getInteger("LRoe") : RoeMath.VIOLATORS));
        msg = !t.hasKey("LMsg") || t.getBoolean("LMsg");
        warnShot = !t.hasKey("LWarn") || t.getBoolean("LWarn");
        waitSec = t.hasKey("LWait") ? Math.max(RoeMath.WAIT_MIN, Math.min(RoeMath.WAIT_MAX, t.getInteger("LWait"))) : 10;
        radius = Math.max(0, t.getInteger("LRadius"));
        NBTTagList l = t.getTagList("LInv", 10);
        for (int i = 0; i < SLOTS; i++) {
            inv[i] = null;
        }
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound c = l.getCompoundTagAt(i);
            int s = c.getByte("Slot") & 0xFF;
            if (s < SLOTS) {
                inv[s] = ItemStack.loadItemStackFromNBT(c);
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        super.writeToNBT(t);
        t.setInteger("LRoe", roe);
        t.setBoolean("LMsg", msg);
        t.setBoolean("LWarn", warnShot);
        t.setInteger("LWait", waitSec);
        t.setInteger("LRadius", radius);
        NBTTagList l = new NBTTagList();
        for (int i = 0; i < SLOTS; i++) {
            if (inv[i] != null) {
                NBTTagCompound c = new NBTTagCompound();
                c.setByte("Slot", (byte) i);
                inv[i].writeToNBT(c);
                l.appendTag(c);
            }
        }
        t.setTag("LInv", l);
    }
}
