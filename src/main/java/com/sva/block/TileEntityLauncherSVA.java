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
import com.sva.ConfigSVA;
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
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;

/**
 * Air-defence wave (mock-up veh_ad_3_launchers): a launcher - block meta GUN (the AA gun, MV, 32 EU/t, «Снаряды ПВО»)
 * or MISSILE (the missile system, EV, 128 EU/t, «Зенитная ракета» - one per tube, 4 tubes). Every 5 ticks it looks at
 * its picture - the command post's (the same owner's within BallisticsMath.LINK) or, without one, its own eyes
 * (BallisticsMath.EYES, the owner's zones) - picks the targets its rule of engagement wants (fire channels: the gun one,
 * the missile system two), runs the staged warning on each (RoeMath.stage) and, once at «Огонь», asks the server's
 * checks (RoeMath.fireBlock) before every burst / launch: only OK fires; warnings still go where warnsAllowed says.
 * The missile system launches only at a target a tracking radar holds (or within its own eyes). Launches and the first
 * burst of an engagement go to the command post's log and logs/airdefense.log.
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
    public int upkeep() {
        return UPKEEP[kind()];
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
        TileEntityCommandPostSVA kp = AirDefenseSVA.INSTANCE.commandPost(worldObj, ownerName, xCoord, yCoord, zCoord, BallisticsMath.LINK);
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

    /** Engaged target ids (world tests). */
    public Map<Integer, long[]> engagements() {
        return eng;
    }

    // ------------------------------------------------------------------ the work

    @Override
    protected void serverTick() {
        if (!tierSet) {
            tierSet = true;
            setTier(kind() == MISSILE ? Tier.EV : Tier.MV);
        }
        if (++timer % 5 != 0) {
            return;
        }
        if (timer % 10 == 0) {
            sendScreens();
        }
        if (!powered) {
            eng.clear();
            return;
        }
        engage(worldObj.getTotalWorldTime());
    }

    /** One decision round (the world test may call it). */
    public void engage(long now) {
        TileEntityCommandPostSVA kp = AirDefenseSVA.INSTANCE.commandPost(worldObj, ownerName, xCoord, yCoord, zCoord, BallisticsMath.LINK);
        if (kp != null && !kp.isPowered()) {
            kp = null;
        }
        final double ax = xCoord + 0.5, ay = yCoord + 1.5, az = zCoord + 0.5;
        List<AirDefenseSVA.Contact> pic;
        if (kp != null) {
            pic = new ArrayList<AirDefenseSVA.Contact>(kp.tracks().values());
        } else {
            pic = AirDefenseSVA.INSTANCE.eyes(worldObj, ax, ay, az, Math.min(range(), BallisticsMath.EYES));
            List<ZoneMath.Zone> zones = AirDefenseDataSVA.get(worldObj) == null ? new ArrayList<ZoneMath.Zone>()
                    : AirDefenseDataSVA.get(worldObj).ofOwner(ownerName, worldObj.provider.dimensionId);
            for (AirDefenseSVA.Contact c : pic) {
                c.rel = relation(c.owner);
                boolean ownFriend = c.rel == 1 || c.rel == 2 && !ConfigSVA.adFireOnFriends;
                c.cls = RoeMath.classify(ownFriend, false, -1, false, false, false, false,
                        ZoneMath.violated(zones, c.x, c.y, c.z, c.course(), true) != null);
            }
        }
        int roeNow = kp != null ? roe : RoeMath.roeWithoutKp(roe);
        boolean warnOnly = !ConfigSVA.adFires();
        boolean pvpOff = ConfigSVA.adRequirePvp && !pvp();
        ChunkCoordinates sp = worldObj.getSpawnPoint();
        boolean nearSpawn = sp != null && Math.hypot(xCoord - sp.posX, zCoord - sp.posZ) < ConfigSVA.adMinSpawnDistance;
        final Map<Integer, Integer> want = new LinkedHashMap<Integer, Integer>();
        final Map<Integer, AirDefenseSVA.Contact> byId = new LinkedHashMap<Integer, AirDefenseSVA.Contact>();
        for (AirDefenseSVA.Contact c : pic) {
            if (c.v == null || c.v.isDead || c.v.isWrecked() || BallisticsMath.dist(ax, ay, az, c.x, c.y, c.z) > range()) {
                continue;
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
                eng.put(id, new long[] {now, RoeMath.ST_NONE, 0});
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
            long elapsed = now - st[0];
            int stage = RoeMath.stage(elapsed, msg, warnShot, wait);
            int toFire = RoeMath.secondsToFire(elapsed, msg, warnShot, wait);
            int block = want.get(e.getKey()).intValue();
            step(c, st, stage, toFire, block, kp, now, ax, ay, az);
            if (kp != null) {
                kp.reportEngaged(c.id, this, stage, toFire);
            }
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
                if (c.zone.length() > 0 || c.cls == RoeMath.VIOLATOR) {
                    ZoneMath.Zone z = zoneOf(kp, c);
                    AirDefenseSVA.INSTANCE.tellPilot(v, "warn", 200, 0xF05050, "sva.ad.msg.warnZone", src, z == null ? c.zone : z.name,
                            String.format("%03d", Integer.valueOf(z == null ? 0 : ZoneMath.exitCourse(z, c.x, c.z))));
                } else {
                    AirDefenseSVA.INSTANCE.tellPilot(v, "warn", 200, 0xF05050, "sva.ad.msg.warnForeign", src);
                }
            }
            if (stage >= RoeMath.ST_LOCK && was < RoeMath.ST_LOCK) {
                AirDefenseSVA.INSTANCE.tellPilot(v, "lock", 200, 0xF05050, "sva.ad.msg.lock", src, String.valueOf(toFire));
                if (kp != null) {
                    kp.log("sva.ad.log.lock", myName(kp), trk);
                }
            }
            if (stage == RoeMath.ST_WARN) {
                warningShot(c, ax, ay, az);
                AirDefenseSVA.INSTANCE.tellPilot(v, "warnShot", 200, 0xF05050, "sva.ad.msg.warnShot", src);
            }
        }
        if (stage >= RoeMath.ST_LOCK) {
            AirDefenseSVA.INSTANCE.paint(v, RadarMath.T_LOCK, ax, az, now + 20);
        }
        if (stage == RoeMath.ST_FIRE && block == RoeMath.OK) {
            boolean shot = kind() == MISSILE ? launchMissile(c, now, ax, ay, az) : burst(c, now, ax, ay, az);
            if (shot && st[2] == 0) {
                st[2] = 1;
                String line = String.format("%s %s (%d %d %d, %s): %s %s (%s, %s, %s)", kind() == MISSILE ? "SAM" : "AA", myName(kp),
                        Integer.valueOf(xCoord), Integer.valueOf(yCoord), Integer.valueOf(zCoord), ownerName, kind() == MISSILE ? "launch at" : "fire at",
                        trk, v.getOwnerName(), v.getVehicleType().key, c.replied ? com.sva.airdefense.XpdrMath.format(c.code) : "----");
                AirDefenseSVA.INSTANCE.launchLog(line);
                if (kp != null) {
                    kp.log(kind() == MISSILE ? "sva.ad.log.launch" : "sva.ad.log.fire", myName(kp), trk);
                }
            } else if (shot && kind() == MISSILE) {
                AirDefenseSVA.INSTANCE.launchLog(String.format("SAM %s (%d %d %d, %s): launch at %s (%s)", myName(kp), Integer.valueOf(xCoord),
                        Integer.valueOf(yCoord), Integer.valueOf(zCoord), ownerName, trk, v.getOwnerName()));
                if (kp != null) {
                    kp.log("sva.ad.log.launch", myName(kp), trk);
                }
            }
        }
    }

    private static ZoneMath.Zone zoneOf(TileEntityCommandPostSVA kp, AirDefenseSVA.Contact c) {
        if (kp == null) {
            return null;
        }
        for (ZoneMath.Zone z : kp.zones()) {
            if (z.name.equals(c.zone)) {
                return z;
            }
        }
        return null;
    }

    /** The point to shoot at: the contact (with its error) led by the vehicle's velocity; no altitude known - the sight's. */
    private double[] aim(AirDefenseSVA.Contact c, double ax, double ay, double az, double speed, int maxTicks) {
        double[] vel = AirDefenseSVA.INSTANCE.velocity(c.v);
        double y = c.altKnown ? c.y : c.v.posY + c.v.height / 2 + RadarMath.jitter(c.id, worldObj.getTotalWorldTime() / 20, 1, RadarMath.ERROR[RadarMath.SURVEY]);
        return BallisticsMath.intercept(ax, ay, az, c.x, y, c.z, vel[0], vel[1], vel[2], speed, maxTicks);
    }

    private boolean burst(AirDefenseSVA.Contact c, long now, double ax, double ay, double az) {
        if (now < nextShot || getEnergyStored() < BallisticsMath.GUN_EU_BURST) {
            return false;
        }
        double[] p = aim(c, ax, ay, az, BallisticsMath.GUN_SPEED, BallisticsMath.SHELL_LIFE);
        if (p == null || !take()) {
            return false;
        }
        nextShot = now + BallisticsMath.GUN_PERIOD;
        removeEnergy(BallisticsMath.GUN_EU_BURST);
        double[] d = BallisticsMath.dir(ax, ay, az, p[0], p[1], p[2]);
        double spread = c.precise() || (c.kinds & 1 << RadarMath.HEIGHT) != 0 ? BallisticsMath.GUN_SPREAD : BallisticsMath.GUN_SPREAD_SURVEY;
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
            AirDefenseSVA.INSTANCE.fire(pr);
        }
        worldObj.playSoundEffect(ax, ay, az, "fireworks.blast_far", 3F, 0.6F);
        shotEvent(EV_BURST, 0);                                     // the model's recoil + muzzle flash
        return true;
    }

    private boolean launchMissile(AirDefenseSVA.Contact c, long now, double ax, double ay, double az) {
        boolean guided = (c.kinds & 1 << RadarMath.TRACK) != 0
                || BallisticsMath.dist(ax, ay, az, c.v.posX, c.v.posY, c.v.posZ) <= BallisticsMath.EYES && (c.kinds & 1 << AirDefenseSVA.EYES_BIT) != 0;
        if (!guided || now < nextShot || getEnergyStored() < BallisticsMath.MISSILE_EU
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
        nextShot = now + BallisticsMath.MISSILE_RELOAD;
        removeEnergy(BallisticsMath.MISSILE_EU);
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
            AirDefenseSVA.INSTANCE.fire(pr);
        }
        worldObj.playSoundEffect(ax, ay, az, "fireworks.blast_far", 3F, 0.8F);
        shotEvent(EV_BURST, 1);
    }

    /** A hit (AirDefenseSVA): a missile's goes to the log. */
    public void onHit(EntityVehicleSVA target, boolean missile, float damage) {
        if (!missile) {
            return;
        }
        TileEntityCommandPostSVA kp = AirDefenseSVA.INSTANCE.commandPost(worldObj, ownerName, xCoord, yCoord, zCoord, BallisticsMath.LINK);
        String trk = "-";
        if (kp != null) {
            AirDefenseSVA.Contact c = kp.tracks().get(Integer.valueOf(target.getEntityId()));
            trk = c == null ? "-" : String.format("%02d", Integer.valueOf(c.no));
            kp.log("sva.ad.log.hit", trk, String.valueOf(Math.round(damage)));
        }
        AirDefenseSVA.INSTANCE.launchLog(String.format("SAM %s (%d %d %d): hit %s (%s, -%d hull)", myName(kp), Integer.valueOf(xCoord), Integer.valueOf(yCoord),
                Integer.valueOf(zCoord), trk, target.getOwnerName(), Integer.valueOf(Math.round(damage))));
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
        TileEntityCommandPostSVA kp = AirDefenseSVA.INSTANCE.commandPost(worldObj, ownerName, xCoord, yCoord, zCoord, BallisticsMath.LINK);
        t.setString("Kp", kp == null ? "" : kp.name());
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
