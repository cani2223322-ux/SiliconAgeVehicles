package com.sva.airdefense;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityCommandPostSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.item.ItemCodeCardSVA;
import com.sva.net.NetADSVA;
import com.sva.vehicle.TuningMath;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/**
 * Air-defence wave: the server side that is not one block's - the loaded radars / launchers / command posts (each
 * registers itself while loaded), the shells and missiles in the air (simple server objects: a position and a velocity a
 * tick, tested against their target's hull - BallisticsMath), who is being painted by which radar (the RWR), the
 * transponders' and RWR's copies on their riders' clients, the zones' copies on every client, messages to pilots, the
 * launch log (logs/airdefense.log) and the screens' commands (NetADSVA.Cmd).
 * <p>
 * Network: a rider's transponder / RWR is sent only when it changed (checked every 10 ticks); the zones when a command
 * post saves them and at login; the command post's picture to its open screens every 10 ticks. Radars scan on their own
 * intervals (RadarMath.INTERVAL), launchers decide every 5 ticks.
 */
public final class AirDefenseSVA {

    public static final AirDefenseSVA INSTANCE = new AirDefenseSVA();

    /** A vehicle seen by a radar (a radar's last scan) or on a command post's picture (its merged track). */
    public static final class Contact {
        public final int id;
        public EntityVehicleSVA v;
        /** The position as known (survey: +-8 blocks), the altitude only if altKnown. */
        public double x, y, z;
        public boolean altKnown;
        /** RadarMath kinds that see it (bits 1 << kind); 1 << 3: a launcher's own eyes. */
        public int kinds;
        public long seen;
        /** The transponder's answer (the surveillance radar interrogates). */
        public boolean replied, ident;
        public int mode = XpdrMath.OFF, code = -1, board;
        public String owner = "";
        public int type;
        public boolean manned;
        /** The command post's: track number, class, relation to the post's owner, zones, assignment, the launchers' stage. */
        public int no, cls = RoeMath.FOREIGN, rel, stage, toFire;
        public String zone = "", restricted = "", assigned = "", engagedBy = "";
        public double vx, vy, vz;
        public boolean said7700, saidOld, saidIdent;

        public Contact(int id) {
            this.id = id;
        }

        public double speedKmh() {
            return Math.sqrt(vx * vx + vz * vz) * 20 * 3.6;
        }

        public double course() {
            return Math.sqrt(vx * vx + vz * vz) < 1e-3 && v != null ? RadarMath.headingOfYaw(v.rotationYaw) : RadarMath.compass(vx, vz);
        }

        public boolean precise() {
            return (kinds & (1 << RadarMath.TRACK | 1 << EYES_BIT)) != 0;
        }
    }

    /** The launchers' own eyes in Contact.kinds. */
    public static final int EYES_BIT = 3;

    /** A shell or a missile in the air. */
    public static final class Projectile {
        public boolean missile, harmless, lost;
        public double x, y, z, vx, vy, vz;
        public int life, target, dim;
        public float damage;
        /** The launcher's key (AirDefenseDataSVA.key) - its missiles in the air, its hits in the log. */
        public String launcher = "";
    }

    private static final class Threat {
        final int kind;
        final double x, z;
        final long until;

        Threat(int kind, double x, double z, long until) {
            this.kind = kind;
            this.x = x;
            this.z = z;
            this.until = until;
        }
    }

    private final Set<TileEntityAirDefenseSVA> tiles = new LinkedHashSet<TileEntityAirDefenseSVA>();
    private final List<Projectile> shots = new ArrayList<Projectile>();
    private final Map<Integer, List<Threat>> paint = new HashMap<Integer, List<Threat>>();
    private final Map<Integer, String> xpdrSent = new HashMap<Integer, String>(), rwrSent = new HashMap<Integer, String>();
    private final Map<Integer, double[]> motion = new HashMap<Integer, double[]>();
    private final Map<String, Long> said = new HashMap<String, Long>();
    private final Random rnd = new Random();
    private int ticks, zonesSent = -1;

    private AirDefenseSVA() {
    }

    // ------------------------------------------------------------------ the loaded blocks

    public void register(TileEntityAirDefenseSVA t) {
        tiles.add(t);
    }

    public void unregister(TileEntityAirDefenseSVA t) {
        tiles.remove(t);
    }

    /** The loaded blocks of a class in a world, valid ones only. */
    @SuppressWarnings("unchecked")
    public <T extends TileEntityAirDefenseSVA> List<T> loaded(World w, Class<T> c) {
        List<T> out = new ArrayList<T>();
        for (TileEntityAirDefenseSVA t : tiles) {
            if (c.isInstance(t) && t.getWorldObj() == w && !t.isInvalid()) {
                out.add((T) t);
            }
        }
        return out;
    }

    /** The nearest working command post of `owner` within `range` of (x, y, z), or null. */
    public TileEntityCommandPostSVA commandPost(World w, String owner, double x, double y, double z, double range) {
        TileEntityCommandPostSVA best = null;
        double bd = range * range;
        for (TileEntityCommandPostSVA k : loaded(w, TileEntityCommandPostSVA.class)) {
            if (!k.getOwnerName().equalsIgnoreCase(owner)) {
                continue;
            }
            double d = k.getDistanceFrom(x, y, z);
            if (d <= bd) {
                bd = d;
                best = k;
            }
        }
        return best;
    }

    public TileEntityLauncherSVA launcher(World w, String key) {
        for (TileEntityLauncherSVA l : loaded(w, TileEntityLauncherSVA.class)) {
            if (l.key().equals(key)) {
                return l;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ painting (the RWR)

    /** A radar / launcher (at x, z) paints this vehicle until `until` with a RadarMath.T_* kind. */
    public void paint(EntityVehicleSVA v, int kind, double x, double z, long until) {
        List<Threat> l = paint.get(Integer.valueOf(v.getEntityId()));
        if (l == null) {
            l = new ArrayList<Threat>();
            paint.put(Integer.valueOf(v.getEntityId()), l);
        }
        for (Iterator<Threat> it = l.iterator(); it.hasNext();) {
            Threat t = it.next();
            if (t.kind == kind && Math.abs(t.x - x) < 0.5 && Math.abs(t.z - z) < 0.5) {
                it.remove();
            }
        }
        if (l.size() < 16) {
            l.add(new Threat(kind, x, z, until));
        }
    }

    /** The threats on a vehicle now: {kind, x, z} (missiles: their position). */
    public List<double[]> threats(EntityVehicleSVA v) {
        List<double[]> out = new ArrayList<double[]>();
        long now = v.worldObj.getTotalWorldTime();
        List<Threat> l = paint.get(Integer.valueOf(v.getEntityId()));
        if (l != null) {
            for (Threat t : l) {
                if (t.until >= now) {
                    out.add(new double[] {t.kind, t.x, t.z});
                }
            }
        }
        for (Projectile p : shots) {
            if (p.missile && !p.lost && p.target == v.getEntityId() && p.dim == v.dimension) {
                out.add(new double[] {RadarMath.T_MISSILE, p.x, p.z});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ motion (the guns' lead, the tracks' speed)

    /** A vehicle's velocity (blocks a tick) over the last ~5 ticks, as the server sees it. */
    public double[] velocity(EntityVehicleSVA v) {
        double[] m = motion.get(Integer.valueOf(v.getEntityId()));
        return m == null ? new double[] {v.motionX, v.motionY, v.motionZ} : new double[] {m[3], m[4], m[5]};
    }

    private void sampleMotion(EntityVehicleSVA v, long now) {
        Integer id = Integer.valueOf(v.getEntityId());
        double[] m = motion.get(id);
        if (m == null) {
            motion.put(id, new double[] {v.posX, v.posY, v.posZ, 0, 0, 0, now});
            return;
        }
        double dt = now - m[6];
        if (dt <= 0) {
            return;
        }
        if (dt > 40) {
            m[3] = m[4] = m[5] = 0;
        } else {
            m[3] = (v.posX - m[0]) / dt;
            m[4] = (v.posY - m[1]) / dt;
            m[5] = (v.posZ - m[2]) / dt;
        }
        m[0] = v.posX;
        m[1] = v.posY;
        m[2] = v.posZ;
        m[6] = now;
    }

    // ------------------------------------------------------------------ detection helpers

    /** Does this block stop a radar beam (stone, earth, metal, wood - not leaves, glass, snow, ice, water, plants)? */
    public static boolean beamStopper(World w, int x, int y, int z) {
        if (!w.blockExists(x, y, z)) {
            return false;
        }
        Block b = w.getBlock(x, y, z);
        Material m = b.getMaterial();
        if (m == Material.air || m == Material.leaves || m == Material.glass || m == Material.snow || m == Material.craftedSnow
                || m == Material.ice || m == Material.packedIce || m == Material.plants || m == Material.vine || m == Material.water
                || m == Material.web || m == Material.cloth || m == Material.carpet || m == Material.circuits) {
            return false;
        }
        return m.isOpaque() && b.isOpaqueCube();
    }

    public static boolean losClear(final World w, double x0, double y0, double z0, double x1, double y1, double z1) {
        return RadarMath.losClear(x0, y0, z0, x1, y1, z1, new RadarMath.Probe() {
            @Override
            public boolean blocks(int x, int y, int z) {
                return beamStopper(w, x, y, z);
            }
        });
    }

    /** The vehicle's centre (hull middle). */
    public static double[] centre(EntityVehicleSVA v) {
        return new double[] {v.posX, v.posY + v.height / 2, v.posZ};
    }

    /** It flies high enough to be seen at all (and isn't the cloaked Singular Ship). */
    public static boolean radarVisible(EntityVehicleSVA v) {
        double rcs = RadarMath.rcs(v.getVehicleType());
        if (!(rcs > 0) || v.isDead) {
            return false;
        }
        boolean cloak = v.getVehicleType().singular && v.state.invisible;
        return RadarMath.visible(rcs, cloak ? 0 : v.heightAboveGround(RadarMath.MIN_AGL + 0.5), cloak);
    }

    /** The detection range multiplier from the vehicle's tuning. */
    public static double stealth(EntityVehicleSVA v) {
        return RadarMath.stealthMul(v.state.tuning, ConfigSVA.tuning);
    }

    /** Is anyone (a player) aboard? */
    public static boolean manned(EntityVehicleSVA v) {
        for (Entity e : v.riders()) {
            if (e instanceof EntityPlayer) {
                return true;
            }
        }
        return false;
    }

    /** A launcher's own eyes: every visible vehicle within `range` of (x, y, z) with a clear line of sight - precise, no reply. */
    public List<Contact> eyes(World w, double x, double y, double z, double range) {
        List<Contact> out = new ArrayList<Contact>();
        long now = w.getTotalWorldTime();
        for (Object o : w.loadedEntityList) {
            if (!(o instanceof EntityVehicleSVA)) {
                continue;
            }
            EntityVehicleSVA v = (EntityVehicleSVA) o;
            double[] c = centre(v);
            double d = BallisticsMath.dist(x, y, z, c[0], c[1], c[2]);
            if (d > range || !radarVisible(v) || !losClear(w, x, y, z, c[0], c[1], c[2])) {
                continue;
            }
            Contact k = new Contact(v.getEntityId());
            fill(k, v, c[0], c[1], c[2], true, now);
            k.kinds = 1 << EYES_BIT;
            out.add(k);
        }
        return out;
    }

    /** The common part of a contact: the vehicle, the position, who owns it, aboard, its velocity. */
    public void fill(Contact k, EntityVehicleSVA v, double x, double y, double z, boolean altKnown, long now) {
        k.v = v;
        k.x = x;
        k.y = y;
        k.z = z;
        k.altKnown = altKnown;
        k.seen = now;
        k.owner = v.getOwnerName();
        k.type = v.getVehicleType().ordinal();
        k.manned = manned(v);
        double[] vel = velocity(v);
        k.vx = vel[0];
        k.vy = vel[1];
        k.vz = vel[2];
    }

    /** The surveillance radar's interrogation: the transponder's answer into the contact (and the ОТВЕТ lamp). */
    public void interrogate(Contact k, EntityVehicleSVA v, long now) {
        XpdrMath.Xpdr x = v.state.xpdr;
        if (!XpdrMath.fitted(v.getVehicleType()) || !x.replying()) {
            k.replied = false;
            k.mode = XpdrMath.fitted(v.getVehicleType()) ? x.mode : XpdrMath.OFF;
            k.code = -1;
            return;
        }
        x.lastInterrogated = now;
        k.replied = true;
        k.mode = x.mode;
        k.code = x.code;
        k.ident = x.identOn(now);
        k.board = XpdrMath.givesBoard(x.mode) ? v.getBoardNumber() : 0;
        if (XpdrMath.givesAltitude(x.mode) && !k.altKnown) {
            k.y = v.posY;
            k.altKnown = true;
        }
    }

    // ------------------------------------------------------------------ shells and missiles

    public void fire(Projectile p) {
        if (shots.size() < 512) {
            shots.add(p);
        }
    }

    /** Missiles of this launcher still in the air (its fire channels). */
    public int missilesOf(String launcher) {
        int n = 0;
        for (Projectile p : shots) {
            if (p.missile && p.launcher.equals(launcher)) {
                n++;
            }
        }
        return n;
    }

    /** World tests: every shot in the air (read only). */
    public List<Projectile> shots() {
        return shots;
    }

    private void moveShots() {
        for (Iterator<Projectile> it = shots.iterator(); it.hasNext();) {
            Projectile p = it.next();
            WorldServer w = DimensionManager.getWorld(p.dim);
            if (w == null || --p.life < 0) {
                if (w != null && p.missile) {
                    burst(w, p.x, p.y, p.z, false);
                }
                it.remove();
                continue;
            }
            Entity te = w.getEntityByID(p.target);
            EntityVehicleSVA target = te instanceof EntityVehicleSVA && !te.isDead ? (EntityVehicleSVA) te : null;
            if (p.missile && target != null && !p.lost) {
                double[] c = centre(target);
                if (p.life % 5 == 0 && !losClear(w, p.x, p.y, p.z, c[0], c[1], c[2])) {
                    p.lost = true;                                  // behind the terrain: the seeker loses it
                } else {
                    double[] vel = velocity(target);
                    double[] aim = BallisticsMath.intercept(p.x, p.y, p.z, c[0], c[1], c[2], vel[0], vel[1], vel[2], BallisticsMath.MISSILE_SPEED, 200);
                    double[] want = aim == null ? BallisticsMath.dir(p.x, p.y, p.z, c[0], c[1], c[2]) : BallisticsMath.dir(p.x, p.y, p.z, aim[0], aim[1], aim[2]);
                    double[] nv = BallisticsMath.steer(new double[] {p.vx, p.vy, p.vz}, want, BallisticsMath.MISSILE_TURN, BallisticsMath.MISSILE_SPEED);
                    p.vx = nv[0];
                    p.vy = nv[1];
                    p.vz = nv[2];
                }
            }
            double ox = p.x, oy = p.y, oz = p.z;
            p.x += p.vx;
            p.y += p.vy;
            p.z += p.vz;
            if (target != null && !p.harmless) {
                double[] c = centre(target);
                double r = p.missile ? BallisticsMath.MISSILE_FUSE + Math.max(target.width, target.height) / 2
                        : Math.max(target.width, target.height) / 2 + 0.6;
                if (BallisticsMath.segToPoint2(ox, oy, oz, p.x, p.y, p.z, c[0], c[1], c[2]) <= r * r) {
                    hit(w, p, target);
                    it.remove();
                    continue;
                }
            }
            int bx = floor(p.x), by = floor(p.y), bz = floor(p.z);
            if (by >= 0 && by < 256 && w.blockExists(bx, by, bz) && w.getBlock(bx, by, bz).getMaterial().blocksMovement()) {
                if (p.missile) {
                    burst(w, p.x, p.y, p.z, false);
                } else {
                    w.func_147487_a("smoke", p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.01);
                }
                it.remove();
                continue;
            }
            if (p.missile) {
                w.func_147487_a("smoke", p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.0);
                w.func_147487_a("flame", ox, oy, oz, 1, 0.0, 0.0, 0.0, 0.0);
            } else if (p.life % 2 == 0) {
                w.func_147487_a("fireworksSpark", p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private void hit(WorldServer w, Projectile p, EntityVehicleSVA target) {
        float dmg = p.damage * ConfigSVA.adDamage;
        target.damageHull(dmg);
        if (p.missile) {
            burst(w, p.x, p.y, p.z, true);
        } else {
            w.func_147487_a("crit", p.x, p.y, p.z, 6, 0.3, 0.3, 0.3, 0.2);
        }
        TileEntityLauncherSVA l = launcher(w, p.launcher);
        if (l != null) {
            l.onHit(target, p.missile, dmg);
        }
    }

    private static void burst(WorldServer w, double x, double y, double z, boolean hit) {
        w.func_147487_a(hit ? "hugeexplosion" : "largeexplode", x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        w.playSoundEffect(x, y, z, "random.explode", hit ? 3F : 1.5F, 1.1F);
    }

    // ------------------------------------------------------------------ messages

    /**
     * A message to a vehicle's riders (players): a chat line «[ПВО] ...» and the big HUD line for 5 s. The same `dedupe`
     * key reaches the same vehicle at most once in `everyTicks` (the launchers repeat themselves every 5 ticks).
     */
    public void tellPilot(EntityVehicleSVA v, String dedupe, int everyTicks, int colour, String key, Object... args) {
        long now = v.worldObj.getTotalWorldTime();
        String k = v.getEntityId() + "|" + dedupe;
        Long last = said.get(k);
        if (last != null && now - last.longValue() < everyTicks && now >= last.longValue()) {
            return;
        }
        if (said.size() > 512) {
            said.clear();
        }
        said.put(k, Long.valueOf(now));
        for (Entity e : v.riders()) {
            if (e instanceof EntityPlayerMP) {
                tell((EntityPlayerMP) e, colour, key, args);
            }
        }
    }

    /** One player: the chat line and the HUD line. */
    public void tell(EntityPlayerMP p, int colour, String key, Object... args) {
        ChatComponentTranslation c = new ChatComponentTranslation(key, args);
        c.getChatStyle().setColor(colour == 0xF05050 ? EnumChatFormatting.RED : colour == 0x62E06A ? EnumChatFormatting.GREEN : EnumChatFormatting.GOLD);
        p.addChatComponentMessage(c);
        NBTTagCompound t = new NBTTagCompound();
        t.setString("K", key);
        NBTTagList a = new NBTTagList();
        for (Object o : args) {
            a.appendTag(new net.minecraft.nbt.NBTTagString(String.valueOf(o)));
        }
        t.setTag("A", a);
        t.setInteger("C", colour);
        NetADSVA.send(p, NetADSVA.D_MSG, t);
    }

    /** logs/airdefense.log (airdefense.launchLog) and the server log. */
    public void launchLog(String line) {
        System.out.println("[SVA-AD] " + line);
        if (!ConfigSVA.adLaunchLog) {
            return;
        }
        MinecraftServer s = MinecraftServer.getServer();
        File f = s == null ? new File("logs", "airdefense.log") : s.getFile("logs/airdefense.log");
        Writer out = null;
        try {
            f.getParentFile().mkdirs();
            out = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
            out.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + " " + line + "\n");
        } catch (Exception e) {
            System.out.println("[SVA-AD] launch log: " + e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception e) {
                    // nothing to do
                }
            }
        }
    }

    // ------------------------------------------------------------------ the transponder

    /** IDENT pressed (the driver): the blip flashes on every radar for 18 s. */
    public void ident(EntityVehicleSVA v) {
        if (XpdrMath.fitted(v.getVehicleType()) && v.state.xpdr.replying()) {
            v.state.xpdr.identUntil = v.worldObj.getTotalWorldTime() + XpdrMath.IDENT_TICKS;
        }
    }

    /** The transponder panel's settings (the driver). */
    public void setXpdr(EntityVehicleSVA v, int mode, int code, boolean dayKey) {
        XpdrMath.Xpdr x = v.state.xpdr;
        long now = v.worldObj.getTotalWorldTime();
        x.mode = Math.max(0, Math.min(XpdrMath.MODES - 1, mode));
        if (XpdrMath.valid(code) && code != x.code) {
            x.setCode(code, now);
            if (dayKey == x.dayKey) {
                dayKey = false;                                     // a code dialled by hand - the card no longer sets it
            }
        }
        x.dayKey = dayKey;
        if (!dayKey) {
            x.cardDay = -1;
        }
    }

    /** «Ключ дня»: the pilot's code card sets the code (every second; today's card preferred). */
    private void cardCode(EntityVehicleSVA v, EntityPlayer pilot, long now) {
        XpdrMath.Xpdr x = v.state.xpdr;
        if (!x.dayKey || !ConfigSVA.adDayKey || pilot == null || pilot.inventory == null) {
            return;
        }
        long today = XpdrMath.day(v.worldObj.getWorldTime());
        ItemStack best = null;
        for (ItemStack s : pilot.inventory.mainInventory) {
            if (ItemCodeCardSVA.printed(s) && (best == null || ItemCodeCardSVA.day(s) == today && ItemCodeCardSVA.day(best) != today
                    || ItemCodeCardSVA.day(s) > ItemCodeCardSVA.day(best) && ItemCodeCardSVA.day(best) != today)) {
                best = s;
            }
        }
        if (best == null) {
            x.cardDay = -1;
            return;
        }
        int code = ItemCodeCardSVA.code(best);
        if (XpdrMath.valid(code) && code != x.code) {
            x.setCode(code, now);
        }
        x.cardDay = ItemCodeCardSVA.day(best);
    }

    // ------------------------------------------------------------------ the tick

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        ticks++;
        try {
            moveShots();
            if (ticks % 5 == 0) {
                vehicles(ticks % 10 == 0, ticks % 20 == 0);
            }
            if (ticks % 20 == 0) {
                syncZones(false);
            }
            if (ticks % 200 == 0) {
                cleanup();
            }
        } catch (Throwable t) {
            System.out.println("[SVA-AD] tick: " + t);
            t.printStackTrace(System.out);
        }
    }

    /** Every 5 ticks: the vehicles' motion; every 10 their riders' transponder / RWR copies; every 20 the code cards. */
    private void vehicles(boolean sync, boolean cards) {
        MinecraftServer s = MinecraftServer.getServer();
        if (s == null || s.worldServers == null) {
            return;
        }
        for (WorldServer w : s.worldServers) {
            if (w == null) {
                continue;
            }
            long now = w.getTotalWorldTime();
            for (Object o : w.loadedEntityList) {
                if (!(o instanceof EntityVehicleSVA)) {
                    continue;
                }
                EntityVehicleSVA v = (EntityVehicleSVA) o;
                if (!v.getVehicleType().movement.flies && !v.getVehicleType().movement.lifts) {
                    continue;
                }
                sampleMotion(v, now);
                if (!sync && !cards) {
                    continue;
                }
                List<EntityPlayerMP> riders = new ArrayList<EntityPlayerMP>();
                for (Entity r : v.riders()) {
                    if (r instanceof EntityPlayerMP) {
                        riders.add((EntityPlayerMP) r);
                    }
                }
                if (riders.isEmpty()) {
                    continue;
                }
                if (cards && v.riddenByEntity instanceof EntityPlayer && XpdrMath.fitted(v.getVehicleType())) {
                    cardCode(v, (EntityPlayer) v.riddenByEntity, now);
                }
                if (sync) {
                    syncRiders(v, riders, w, now);
                }
            }
        }
    }

    private void syncRiders(EntityVehicleSVA v, List<EntityPlayerMP> riders, World w, long now) {
        Integer id = Integer.valueOf(v.getEntityId());
        StringBuilder who = new StringBuilder();
        for (EntityPlayerMP p : riders) {
            who.append(p.getEntityId()).append(',');
        }
        if (XpdrMath.fitted(v.getVehicleType())) {
            XpdrMath.Xpdr x = v.state.xpdr;
            long today = XpdrMath.day(w.getWorldTime());
            String sig = x.mode + ":" + x.code + ":" + x.dayKey + ":" + x.identOn(now) + ":" + x.keyLamp(today) + ":" + x.replyLamp(now) + ":" + who;
            if (!sig.equals(xpdrSent.get(id))) {
                xpdrSent.put(id, sig);
                NBTTagCompound t = new NBTTagCompound();
                t.setInteger("E", v.getEntityId());
                t.setInteger("M", x.mode);
                t.setInteger("C", x.code);
                t.setBoolean("D", x.dayKey);
                t.setInteger("I", x.identOn(now) ? (int) (x.identUntil - now) : 0);
                t.setBoolean("K", x.keyLamp(today));
                t.setBoolean("R", x.replyLamp(now));
                for (EntityPlayerMP p : riders) {
                    NetADSVA.send(p, NetADSVA.D_XPDR, t);
                }
            }
        }
        boolean rwr = ConfigSVA.tuning && TuningMath.has(v.state.tuning, TuningMath.RWR);
        List<double[]> th = rwr ? threats(v) : new ArrayList<double[]>();
        StringBuilder sig = new StringBuilder(who.toString());
        for (double[] t : th) {
            sig.append(';').append((int) t[0]).append(',').append(Math.round(t[1])).append(',').append(Math.round(t[2]));
        }
        String sg = sig.toString();
        if (!sg.equals(rwrSent.get(id))) {
            rwrSent.put(id, sg);
            NBTTagCompound t = new NBTTagCompound();
            t.setInteger("E", v.getEntityId());
            t.setBoolean("On", rwr);
            NBTTagList l = new NBTTagList();
            for (double[] k : th) {
                NBTTagCompound c = new NBTTagCompound();
                c.setByte("K", (byte) k[0]);
                c.setDouble("X", k[1]);
                c.setDouble("Z", k[2]);
                l.appendTag(c);
            }
            t.setTag("T", l);
            for (EntityPlayerMP p : riders) {
                NetADSVA.send(p, NetADSVA.D_RWR, t);
            }
        }
    }

    /** Forgets expired paint, gone vehicles' motion and sent copies. */
    private void cleanup() {
        for (Iterator<Map.Entry<Integer, List<Threat>>> it = paint.entrySet().iterator(); it.hasNext();) {
            List<Threat> l = it.next().getValue();
            long newest = Long.MIN_VALUE;
            for (Threat t : l) {
                newest = Math.max(newest, t.until);
            }
            boolean old = true;
            MinecraftServer s = MinecraftServer.getServer();
            if (s != null && s.worldServers != null && s.worldServers.length > 0 && s.worldServers[0] != null) {
                old = newest < s.worldServers[0].getTotalWorldTime() - 200;
            }
            if (old) {
                it.remove();
            }
        }
        if (motion.size() > 256) {
            motion.clear();
        }
        if (xpdrSent.size() > 256) {
            xpdrSent.clear();
        }
        if (rwrSent.size() > 256) {
            rwrSent.clear();
        }
    }

    // ------------------------------------------------------------------ zones for the pilots

    /** The zones pilots see (show = true) of every dimension, when they changed (or `force`). */
    public void syncZones(boolean force) {
        MinecraftServer s = MinecraftServer.getServer();
        if (s == null || s.worldServers == null || s.worldServers.length == 0 || s.worldServers[0] == null) {
            return;
        }
        AirDefenseDataSVA d = AirDefenseDataSVA.get(s.worldServers[0]);
        if (d == null || !force && d.version() == zonesSent) {
            return;
        }
        zonesSent = d.version();
        NetADSVA.sendToAll(NetADSVA.D_ZONES, zonesTag(d));
    }

    private static NBTTagCompound zonesTag(AirDefenseDataSVA d) {
        NBTTagCompound t = new NBTTagCompound();
        NBTTagList l = new NBTTagList();
        if (ConfigSVA.adEnabled) {
            for (ZoneMath.Zone z : d.all()) {
                if (z.show) {
                    NBTTagCompound c = new NBTTagCompound();
                    z.write(c);
                    l.appendTag(c);
                }
            }
        }
        t.setTag("Z", l);
        return t;
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.player instanceof EntityPlayerMP) {
            AirDefenseDataSVA d = AirDefenseDataSVA.get(e.player.worldObj);
            if (d != null) {
                NetADSVA.send((EntityPlayerMP) e.player, NetADSVA.D_ZONES, zonesTag(d));
            }
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        for (TileEntityCommandPostSVA k : loadedAll(TileEntityCommandPostSVA.class)) {
            k.removeViewer(e.player);
        }
    }

    private <T extends TileEntityAirDefenseSVA> List<T> loadedAll(Class<T> c) {
        List<T> out = new ArrayList<T>();
        for (TileEntityAirDefenseSVA t : tiles) {
            if (c.isInstance(t)) {
                out.add(c.cast(t));
            }
        }
        return out;
    }

    /** Server stopped: nothing kept for the next world. */
    public void clear() {
        tiles.clear();
        shots.clear();
        paint.clear();
        motion.clear();
        xpdrSent.clear();
        rwrSent.clear();
        said.clear();
        zonesSent = -1;
    }

    // ------------------------------------------------------------------ the screens' commands

    public void command(EntityPlayerMP p, NetADSVA.Cmd m) {
        if (p == null || p.worldObj == null) {
            return;
        }
        World w = p.worldObj;
        if (m.op == NetADSVA.XPDR_SET || m.op == NetADSVA.XPDR_IDENT) {
            Entity e = w.getEntityByID(m.entity);
            if (!(e instanceof EntityVehicleSVA) || e.riddenByEntity != p || !XpdrMath.fitted(((EntityVehicleSVA) e).getVehicleType())) {
                return;                                             // only the driver works the transponder
            }
            EntityVehicleSVA v = (EntityVehicleSVA) e;
            if (m.op == NetADSVA.XPDR_IDENT) {
                ident(v);
            } else {
                setXpdr(v, m.data.getInteger("M"), m.data.getInteger("C"), m.data.getBoolean("D"));
            }
            return;
        }
        if (p.getDistanceSq(m.x + 0.5, m.y + 0.5, m.z + 0.5) > 100 || !w.blockExists(m.x, m.y, m.z)) {
            return;
        }
        TileEntity te = w.getTileEntity(m.x, m.y, m.z);
        if (m.op == NetADSVA.KP_CLOSE) {
            if (te instanceof TileEntityCommandPostSVA) {
                ((TileEntityCommandPostSVA) te).removeViewer(p);
            }
            return;
        }
        if (!(te instanceof TileEntityAirDefenseSVA) || !((TileEntityAirDefenseSVA) te).mayUse(p)) {
            return;
        }
        if (m.op == NetADSVA.KP_CMD && te instanceof TileEntityCommandPostSVA) {
            ((TileEntityCommandPostSVA) te).command(p, m.data);
        } else if (m.op == NetADSVA.LAUNCHER_SET && te instanceof TileEntityLauncherSVA) {
            ((TileEntityLauncherSVA) te).applySettings(m.data);
        }
    }
}
