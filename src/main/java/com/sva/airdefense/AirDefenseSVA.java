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
 * <p>
 * Audit 2026-10-22 (the friend / foe trust model): a transponder answers only with the board's power and not as a wreck
 * (xpdrOn - the interrogation, IDENT, the towers' IFF and traffic); a radar / launcher keeps an explicit link to ONE command
 * post (linkedPost: chosen on placement, changed on its screen, the nearest powered one when it has none) and a post is
 * only ever looked for among the powered ones; a launcher whose post is off or unloaded reads the owner's posts' trust data
 * (trust(): the day code, own codes, white list, trusted airports, the operator's decisions) and interrogates what it sees
 * itself; the towers' IFF handoffs are kept by the vehicle's persistent id in AirDefenseDataSVA.
 * <p>
 * Code-only wave (docs/proposal-ad-strict-iff.md): the interrogation carries the card the code came from (never a code);
 * classifyByCode classes a contact by the key's code alone for a code-only post and for a launcher linked to one
 * (codeOnlyPost - only the LINKED post's data, item 15), with the 7700 grace and the «ПОДБОР» memory per air defence owner,
 * and lights the transponder's ОПОЗН lamp (sent to the riders with the card's «5 / 1 МИН»); acceptedBeforeFire is a
 * launcher's silent look before each shot (item 14); a pilot boarding without a card of a code-only post he belongs to is
 * told (item 22); a code-only post's zones go to the clients marked «CO».
 * <p>
 * Audit 2026-10-22 (combat effectiveness and terrain): a beam (radar, a launcher's eyes, a missile's seeker, the coverage
 * map) is stopped by slabs, stairs and fences too and, in an unloaded chunk, by the terrain memory's last known ground
 * (BeamProbe, AdTerrainSVA - bug 10); a shell's / missile's step is walked block by block before the hit test, leaving
 * through its own launcher (ShotProbe - bug 4); the RWR's paint list drops the expired paint first (bug 16); Mode C reports
 * the aircraft's centre like the radars; the launch log is written in batches on a writer thread.
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
        /**
         * Wave A (the command post's): the 7600 state (NoRadioMath.S_*, -1 not squawking it) and its seconds to decide; the
         * IFF state at the towers (IffMath.*); handed to the air defence by a tower (said in the log); the friendly airport
         * whose code it squawks ("" none).
         */
        public int nr = -1, nrLeft, iff;
        public boolean saidIff;
        public String airport = "";
        /**
         * Audit 2026-10-22: the vehicle's persistent id (VehicleState.vehId - the operator's decisions are kept by it); the
         * command post's track: made when, last interrogated when (-1 never: UNKNOWN until then, bug 15); «только вести» on
         * it (no fire from the post's launchers); 7500 said in the log.
         */
        public String vid = "";
        public long born = -1, askedAt = -1;
        public boolean held, said7500;
        /**
         * Audit 2026-10-22 (bug 3): when the position (x, z) and the altitude (y) were measured (total world ticks; -1 never) -
         * a gun leads the mark by its age (BallisticsMath.led).
         */
        public long posAt = -1, altAt = -1;
        /**
         * Code-only wave (proposal-ad-strict-iff items 4, 5, 39): classed the code-only way (a code-only post's track, or a
         * launcher linked to one); the verdict's reason and the miss behind it (RoeMath.ID_*: classifyByCode's idReason /
         * idMissOf); the misses in a row (two at least confirmSeconds apart make a target - «ПРОВ 1/2» until then).
         */
        public boolean codeOnly;
        public int idr, idMiss;
        public final CodeOnlyMath.Confirm confirm = new CodeOnlyMath.Confirm();
        /**
         * Code-only wave, from the interrogation (interrogate): the code came from a card (XpdrMath.Xpdr.fromCard) and that
         * card - whose key, its generation, its periods [cardFirst, cardUntil) - never a code (the post works the key's code
         * out itself); when the code now squawked was set (the guessing guard: it stood 3 s); who flies it (the guesses are
         * counted by the aircraft and by the pilot, «верный код у чужого борта» names him).
         */
        public boolean fromCard;
        public String cardOwner = "", pilot = "";
        public int cardGen;
        public long cardFirst = -1, cardUntil = -1, codeSince = -1;
        /**
         * Code-only wave (the command post's): the 7700 state for this air defence's owner (NoRadioMath.S_*, -1 not squawking
         * it; CodeOnlyMath.emergencyState) and its grace left in seconds (-1 no limit); said in the log: the target confirmed
         * («не опознан»), the key's code from a stranger («верный код у чужого борта»), the 7700 grace used up.
         */
        public int em = -1, emLeft;
        public boolean saidCo, saidStranger, saidEmOver;
        /**
         * Review 2026-10-27: the interrogation whose code was last weighed as a guess (-1 none) - the post goes over one survey
         * answer for up to 6 s, and each answer counts once.
         */
        public long probeAt = -1;

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
        /** Audit 2026-10-22 (bug 4): the launcher's master block and its structure's cells - the shell leaves through them. */
        public int hx, hy, hz;
        public int[][] homeCells;

        /** Fired from this block's structure. */
        public void from(TileEntityAirDefenseSVA t) {
            hx = t.xCoord;
            hy = t.yCoord;
            hz = t.zCoord;
            homeCells = t.cells();
        }

        /** A block of the launcher's own structure. */
        public boolean home(int x, int y, int z) {
            if (homeCells == null) {
                return false;
            }
            for (int[] c : homeCells) {
                if (x == hx + c[0] && y == hy + c[1] && z == hz + c[2]) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Set<TileEntityAirDefenseSVA> tiles = new LinkedHashSet<TileEntityAirDefenseSVA>();
    private final List<Projectile> shots = new ArrayList<Projectile>();
    private final Map<Integer, List<RadarMath.Paint>> paint = new HashMap<Integer, List<RadarMath.Paint>>();
    private final Map<Integer, String> xpdrSent = new HashMap<Integer, String>(), rwrSent = new HashMap<Integer, String>();
    /** Review 2026-10-27 (batch 3): each vehicle's card holders at its last card round (entity ids) - a change runs one at once. */
    private final Map<Integer, String> cardWho = new HashMap<Integer, String>();
    private final Map<Integer, double[]> motion = new HashMap<Integer, double[]>();
    private final Map<String, Long> said = new HashMap<String, Long>();
    /** Wave A: the reverser / afterburner last seen per vehicle (the RCS bump). The IFF handoffs: AirDefenseDataSVA (audit 2026-10-22). */
    private final Map<Integer, Long> bumpSeen = new HashMap<Integer, Long>();
    /** Audit 2026-10-22: the command post's commands per player (lower-case name): CmdRateMath's LIGHT / HEAVY next times, the last «не так часто». */
    private final Map<String, long[]> cmdRate = new HashMap<String, long[]>();
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

    /**
     * The nearest working (powered - audit 2026-10-22, bug 19: an unpowered nearer post no longer hides a powered farther one)
     * command post of `owner` within `range` of (x, y, z), or null.
     */
    public TileEntityCommandPostSVA commandPost(World w, String owner, double x, double y, double z, double range) {
        TileEntityCommandPostSVA best = null;
        double bd = range * range;
        for (TileEntityCommandPostSVA k : loaded(w, TileEntityCommandPostSVA.class)) {
            if (!k.getOwnerName().equalsIgnoreCase(owner) || !k.isPowered()) {
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

    /**
     * Audit 2026-10-22 (bug 19): the command post a radar / launcher answers to - its saved link (TileEntityAirDefenseSVA
     * kpLink) when that post is loaded (powered or not: the caller checks); the link kept but no post while the linked post's
     * chunk is unloaded (its trust data still known); a link to a post that is gone (broken, another owner's now) dropped. No
     * link: the nearest powered post of the owner within LINK, remembered as the link.
     */
    public TileEntityCommandPostSVA linkedPost(TileEntityAirDefenseSVA t) {
        World w = t.getWorldObj();
        if (w == null || w.isRemote) {
            return null;
        }
        String k = t.kpLink();
        double cx = t.xCoord + 0.5, cy = t.yCoord + 0.5, cz = t.zCoord + 0.5, r2 = BallisticsMath.LINK * BallisticsMath.LINK;
        if (k.length() > 0) {
            for (TileEntityCommandPostSVA p : loaded(w, TileEntityCommandPostSVA.class)) {
                if (p.key().equals(k)) {
                    if (p.getOwnerName().equalsIgnoreCase(t.getOwnerName()) && p.getDistanceFrom(cx, cy, cz) <= r2) {
                        return p;
                    }
                    break;
                }
            }
            AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
            AirDefenseDataSVA.KpInfo i = d == null ? null : d.info(k, false);
            if (i != null && i.owner.equalsIgnoreCase(t.getOwnerName()) && !loadedKey(w, k)) {
                return null;                                        // unloaded: no picture, its trust data still holds
            }
            t.setKpLink("");
        }
        TileEntityCommandPostSVA best = commandPost(w, t.getOwnerName(), cx, cy, cz, BallisticsMath.LINK);
        if (best != null) {
            t.setKpLink(best.key());
        }
        return best;
    }

    private boolean loadedKey(World w, String key) {
        for (TileEntityCommandPostSVA p : loaded(w, TileEntityCommandPostSVA.class)) {
            if (p.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** The linked post when it is powered (its picture and orders apply), else null. */
    public TileEntityCommandPostSVA workingPost(TileEntityAirDefenseSVA t) {
        TileEntityCommandPostSVA k = linkedPost(t);
        return k != null && k.isPowered() ? k : null;
    }

    /**
     * The radar's / launcher's screen: the link moves to the owner's next loaded post within LINK (nearest first, after the
     * one linked now; past the last - the first). @return the post linked now (null none in reach)
     */
    public TileEntityCommandPostSVA cycleLink(TileEntityAirDefenseSVA t) {
        TileEntityCommandPostSVA next = nextLink(t);
        t.setKpLink(next == null ? "" : next.key());
        return next;
    }

    /**
     * The post cycleLink would link to now (the owner's next loaded post within LINK, nearest first, after the one linked now;
     * past the last - the first), nothing changed. @return null - none in reach
     */
    public TileEntityCommandPostSVA nextLink(TileEntityAirDefenseSVA t) {
        World w = t.getWorldObj();
        final double cx = t.xCoord + 0.5, cy = t.yCoord + 0.5, cz = t.zCoord + 0.5;
        List<TileEntityCommandPostSVA> l = new ArrayList<TileEntityCommandPostSVA>();
        for (TileEntityCommandPostSVA p : loaded(w, TileEntityCommandPostSVA.class)) {
            if (p.getOwnerName().equalsIgnoreCase(t.getOwnerName()) && p.getDistanceFrom(cx, cy, cz) <= BallisticsMath.LINK * BallisticsMath.LINK) {
                l.add(p);
            }
        }
        java.util.Collections.sort(l, new java.util.Comparator<TileEntityCommandPostSVA>() {
            @Override
            public int compare(TileEntityCommandPostSVA a, TileEntityCommandPostSVA b) {
                return Double.compare(a.getDistanceFrom(cx, cy, cz), b.getDistanceFrom(cx, cy, cz));
            }
        });
        if (l.isEmpty()) {
            return null;
        }
        int i = 0;
        for (int k = 0; k < l.size(); k++) {
            if (l.get(k).key().equals(t.kpLink())) {
                i = (k + 1) % l.size();
            }
        }
        return l.get(i);
    }

    /**
     * The radar's / launcher's link button (NetADSVA.KP_LINK): the link moves on (cycleLink). Code-only wave (item 25): a
     * launcher's link is its owner's alone while it is linked to a code-only post - review 2026-10-27 (batch 3): and when the
     * next post in the cycle is one (an access-list member set «По всем чужим» under a usual post and moved the launcher to
     * the owner's code-only one: it fired «по всем без кода (и ваши)» on the owner's behalf, a rule he never set).
     * @return relinked (false: refused, the player told)
     */
    public boolean relink(EntityPlayer p, TileEntityAirDefenseSVA te) {
        if (te instanceof TileEntityLauncherSVA) {
            TileEntityLauncherSVA l = (TileEntityLauncherSVA) te;
            TileEntityCommandPostSVA next = nextLink(l);
            if (CodeOnlyMath.relinkRefused(l.ownerOnly(p), next != null && l.ownerOnlyAt(p, next.key()))) {
                if (p instanceof EntityPlayerMP) {
                    tell((EntityPlayerMP) p, 0xF0B040, "sva.ad.msg.coOwnerOnly");
                }
                return false;
            }
        }
        cycleLink(te);                                              // audit 2026-10-22 (bug 19): the next post in reach
        return true;
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

    /**
     * A radar / launcher (at x, z) paints this vehicle until `until` with a RadarMath.T_* kind (audit 2026-10-22, bug 16:
     * RadarMath.paint - the expired paint goes first, a full list makes room).
     */
    public void paint(EntityVehicleSVA v, int kind, double x, double z, long until) {
        List<RadarMath.Paint> l = paint.get(Integer.valueOf(v.getEntityId()));
        if (l == null) {
            l = new ArrayList<RadarMath.Paint>();
            paint.put(Integer.valueOf(v.getEntityId()), l);
        }
        RadarMath.paint(l, new RadarMath.Paint(kind, x, z, until), v.worldObj.getTotalWorldTime());
    }

    /**
     * The threats on a vehicle now: {kind, x, z} (missiles: their position). Audit 2026-10-22 (the RWR): each launcher with
     * a missile in the air at it once more as RadarMath.T_LAUNCH at its block - the scope circles the launching site.
     */
    public List<double[]> threats(EntityVehicleSVA v) {
        List<double[]> out = new ArrayList<double[]>();
        long now = v.worldObj.getTotalWorldTime();
        List<RadarMath.Paint> l = paint.get(Integer.valueOf(v.getEntityId()));
        if (l != null) {
            for (RadarMath.Paint t : l) {
                if (t.until >= now) {
                    out.add(new double[] {t.kind, t.x, t.z});
                }
            }
        }
        List<String> sites = new ArrayList<String>();
        for (Projectile p : shots) {
            if (p.missile && !p.lost && p.target == v.getEntityId() && p.dim == v.dimension) {
                out.add(new double[] {RadarMath.T_MISSILE, p.x, p.z});
                String site = p.hx + "," + p.hz;
                if (p.homeCells != null && !sites.contains(site)) {
                    sites.add(site);
                    out.add(new double[] {RadarMath.T_LAUNCH, p.hx + 0.5, p.hz + 0.5});
                }
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

    /**
     * Does this block stop a radar beam (stone, earth, metal, wood - not leaves, glass, snow, ice, water, plants)? Audit
     * 2026-10-22 (bug 10): a solid shape that is not a full cube stops it too - slabs, stairs, fences, walls, doors (its
     * collision box; asked only with the neighbours' chunks loaded, else taken as solid - no chunk is loaded for a beam).
     * The air-defence structures themselves are models over thin frames and never stop a beam (the radar's own mast, the
     * launcher's own mount).
     */
    public static boolean stops(World w, Block b, int x, int y, int z) {
        if (b == null || b instanceof com.sva.block.BlockAirDefenseSVA || b instanceof com.sva.block.BlockAdPartSVA) {
            return false;
        }
        Material m = b.getMaterial();
        if (m == Material.air || m == Material.leaves || m == Material.glass || m == Material.snow || m == Material.craftedSnow
                || m == Material.ice || m == Material.packedIce || m == Material.plants || m == Material.vine || m == Material.water
                || m == Material.web || m == Material.cloth || m == Material.carpet || m == Material.circuits) {
            return false;
        }
        if (!m.isOpaque()) {
            return false;
        }
        if (b.isOpaqueCube()) {
            return true;
        }
        if (!w.checkChunksExist(x - 1, y, z - 1, x + 1, y, z + 1)) {
            return true;
        }
        return b.getCollisionBoundingBoxFromPool(w, x, y, z) != null;
    }

    /**
     * A block on a beam: the loaded world's (stops), an unloaded column's last known ground (audit 2026-10-22, bug 10:
     * AdTerrainSVA - opaque below it; never seen - transparent as before).
     */
    public static boolean beamStopper(World w, int x, int y, int z) {
        return new BeamProbe(w).blocks(x, y, z);
    }

    /**
     * Audit 2026-10-22 (bug 10): a beam's blocks - radars, a launcher's own eyes, a missile's seeker, the coverage map: the
     * loaded chunk's block, else the terrain memory's column. The chunk is kept between the beam's blocks (it changes rarely
     * along a line).
     */
    public static final class BeamProbe implements RadarMath.Probe {
        private final World w;
        private int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
        private net.minecraft.world.chunk.Chunk chunk;
        private byte[] col;

        public BeamProbe(World w) {
            this.w = w;
        }

        /** The chunk of (x, z): loaded (chunk) or remembered (col); false - neither. */
        boolean at(int x, int z) {
            int kx = x >> 4, kz = z >> 4;
            if (kx != cx || kz != cz) {
                cx = kx;
                cz = kz;
                chunk = w.getChunkProvider().chunkExists(kx, kz) ? w.getChunkFromChunkCoords(kx, kz) : null;
                col = chunk == null ? AdTerrainSVA.INSTANCE.column(w, kx, kz) : null;
            }
            return chunk != null || col != null;
        }

        @Override
        public boolean blocks(int x, int y, int z) {
            if (y < 0 || y > 255 || !at(x, z)) {
                return false;
            }
            return chunk != null ? stops(w, chunk.getBlock(x & 15, y, z & 15), x, y, z) : TerrainMath.opaque(col, x, y, z);
        }

        /** The ground of a column (TerrainMath.ground; Integer.MIN_VALUE - not known). */
        public int ground(final int x, final int z) {
            if (!at(x, z)) {
                return Integer.MIN_VALUE;
            }
            if (chunk == null) {
                return TerrainMath.height(col, x, z);
            }
            return TerrainMath.ground(top(x, z), column(x, z));
        }

        /**
         * A loaded column's ground and the bottom of its top solid layer (TerrainMath.bottom - the terrain memory keeps both;
         * audit review 2026-10-22). null - the chunk is not loaded.
         */
        public int[] layer(int x, int z) {
            if (!at(x, z) || chunk == null) {
                return null;
            }
            TerrainMath.Column cl = column(x, z);
            int g = TerrainMath.ground(top(x, z), cl);
            return new int[] {g, TerrainMath.bottom(g, cl)};
        }

        private int top(int x, int z) {
            return Math.max(chunk.getHeightValue(x & 15, z & 15), chunk.getPrecipitationHeight(x & 15, z & 15));
        }

        private TerrainMath.Column column(final int x, final int z) {
            final net.minecraft.world.chunk.Chunk c = chunk;
            final int lx = x & 15, lz = z & 15;
            return new TerrainMath.Column() {
                @Override
                public boolean stops(int y) {
                    return AirDefenseSVA.stops(w, c.getBlock(lx, y, lz), x, y, z);
                }

                @Override
                public boolean water(int y) {
                    return c.getBlock(lx, y, lz).getMaterial() == Material.water;
                }
            };
        }
    }

    /** The ground of a column as the coverage map and the terrain memory see it (Integer.MIN_VALUE - not known). */
    public static int ground(World w, int x, int z) {
        return new BeamProbe(w).ground(x, z);
    }

    public static boolean losClear(final World w, double x0, double y0, double z0, double x1, double y1, double z1) {
        return RadarMath.losClear(x0, y0, z0, x1, y1, z1, new BeamProbe(w));
    }

    /**
     * Audit 2026-10-22 (bug 4): a shell's / missile's blocks - whatever blocks movement in a loaded chunk (but the launcher's
     * own structure it leaves through), an unloaded column below its last known ground.
     */
    private static final class ShotProbe implements RadarMath.Probe {
        private final BeamProbe at;
        private final Projectile p;

        ShotProbe(World w, Projectile p) {
            this.at = new BeamProbe(w);
            this.p = p;
        }

        @Override
        public boolean blocks(int x, int y, int z) {
            if (y < 0 || y > 255 || p.home(x, y, z) || !at.at(x, z)) {
                return false;
            }
            return at.chunk != null ? at.chunk.getBlock(x & 15, y, z & 15).getMaterial().blocksMovement() : TerrainMath.opaque(at.col, x, y, z);
        }
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

    /** The detection range multiplier from the vehicle's tuning - wave A: and what sticks out of it now (extras). */
    public static double stealth(EntityVehicleSVA v) {
        double m = RadarMath.stealthMul(v.state.tuning, ConfigSVA.tuning);
        return ConfigSVA.adRcsExtras ? m * extras(v) : m;
    }

    /**
     * Wave A (plan-next §9 A3): the gear out (the liner's by its sequence, a retracting gear by height and speed), stores on
     * the pylons (an extra tank on an aircraft), the liner's cargo doors open, the reverser / afterburner a moment ago.
     */
    public static double extras(EntityVehicleSVA v) {
        com.sva.vehicle.VehicleType t = v.getVehicleType();
        com.sva.vehicle.LinerSystems L = v.state.liner();
        boolean gear, doors = false;
        if (L != null) {
            gear = L.gear.pos < 0.9;
            doors = (L.doorsOpen() & (com.sva.vehicle.LinerSystems.D_CARGO_F | com.sva.vehicle.LinerSystems.D_CARGO_A)) != 0;
        } else {
            double[] vel = INSTANCE.velocity(v);
            gear = RadarMath.gearOut(com.sva.vehicle.GearMath.retracts(t), v.heightAboveGround(10), Math.sqrt(vel[0] * vel[0] + vel[2] * vel[2]));
        }
        boolean stores = t.movement.flies && ConfigSVA.tuning && TuningMath.has(v.state.tuning, TuningMath.EXTRA_TANK);
        Long seen = INSTANCE.bumpSeen.get(Integer.valueOf(v.getEntityId()));
        boolean bump = seen != null && RadarMath.bumpOn(seen.longValue(), v.worldObj.getTotalWorldTime());
        return RadarMath.extrasMul(gear, stores, doors, bump);
    }

    /** The reverser deployed / the afterburner lit now (the bump's source). */
    static boolean bumping(EntityVehicleSVA v) {
        com.sva.vehicle.LinerSystems L = v.state.liner();
        if (L != null && (L.afs.revPos[0] > 0.05 || L.afs.revPos[1] > 0.05)) {
            return true;
        }
        return v.isBoosting() && v.getVehicleType().gases.boostGas != null;
    }

    /** World tests: the bump as if the reverser / afterburner were seen now. */
    public void debugBump(EntityVehicleSVA v, long at) {
        bumpSeen.put(Integer.valueOf(v.getEntityId()), Long.valueOf(at));
    }

    // ------------------------------------------------------------------ wave A: IFF handoffs (an intruder for the posts)

    /**
     * A tower hands an aircraft to the air defence until `until` (audit 2026-10-22, bug 20: kept in AirDefenseDataSVA by the
     * vehicle's persistent id and the tower - a chunk reload or a restart no longer forgets it, two towers no longer
     * overwrite each other).
     */
    public void handoff(World w, String vid, String towerKey, String owner, String icao, long until) {
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        if (d != null && vid != null && vid.length() > 0) {
            d.handoff(vid, towerKey, owner, icao, until);
        }
    }

    /** A tower takes its handoff back (towerKey null: every tower's). */
    public void release(World w, String vid, String towerKey) {
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        if (d != null && vid != null) {
            d.release(vid, towerKey);
        }
    }

    /** The handoffs of an aircraft still in force (empty none). */
    public List<AirDefenseDataSVA.Handoff> handoffs(World w, String vid, long now) {
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        return d == null || vid == null ? new ArrayList<AirDefenseDataSVA.Handoff>() : d.handoffs(vid, now);
    }

    /** One handoff of an aircraft still in force (null none) - the world tests. */
    public AirDefenseDataSVA.Handoff handedOff(World w, String vid, long now) {
        List<AirDefenseDataSVA.Handoff> l = handoffs(w, vid, now);
        return l.isEmpty() ? null : l.get(0);
    }

    /** A vehicle's persistent id (VehicleState.vehId; an entity-id stand-in for a vehicle without one). */
    public static String vehKey(EntityVehicleSVA v) {
        String id = v == null ? null : v.state.vehId;
        return id == null || id.length() == 0 ? "e" + (v == null ? 0 : v.getEntityId()) : id;
    }

    /**
     * Audit 2026-10-22 (4.2): the transponder has power - not a wreck, and the board on: the liner's electrics, the board
     * systems' battery (АКБ on, not flat) of the light plane / jet / tiltrotor, the energy of the rest.
     */
    public static boolean xpdrPowered(EntityVehicleSVA v) {
        if (v == null || v.isWrecked()) {
            return false;
        }
        com.sva.vehicle.VehicleType t = v.getVehicleType();
        if (t == com.sva.vehicle.VehicleType.LINER) {
            return v.state.liner() != null && v.state.liner().powered();
        }
        if (com.sva.vehicle.SystemsMath.applies(t)) {
            return v.boardOn();
        }
        return v.getEnergy() > 0;
    }

    /**
     * The transponder answers now: fitted, in A / C / S, powered (no answer from a dark board or a wreck). Code-only wave
     * (decision 3): fitted on every vehicle with airdefense.allTransponders - a ground vehicle's answers too, but only a radar's
     * contact is ever interrogated (radarVisible: the buggy, the crawler and the drill never; the hoverbike 4+ blocks up).
     */
    public static boolean xpdrOn(EntityVehicleSVA v) {
        return v != null && XpdrMath.fitted(v.getVehicleType()) && v.state.xpdr.replying() && xpdrPowered(v);
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
        k.posAt = now;
        k.altAt = altKnown ? now : -1;
        k.owner = v.getOwnerName();
        k.type = v.getVehicleType().ordinal();
        k.manned = manned(v);
        k.vid = vehKey(v);
        double[] vel = velocity(v);
        k.vx = vel[0];
        k.vy = vel[1];
        k.vz = vel[2];
    }

    /** The surveillance radar's interrogation: the transponder's answer into the contact (and the ОТВЕТ lamp). */
    public void interrogate(Contact k, EntityVehicleSVA v, long now) {
        interrogate(k, v, now, false);
    }

    /**
     * The interrogation; `silent` (code-only wave, item 14: a launcher's look at its target's transponder before each burst /
     * launch) leaves the ОТВЕТ lamp alone. Code-only wave: the card the code came from (never a code - whose key, generation,
     * periods), when the code was set and who flies it go with the answer.
     */
    public void interrogate(Contact k, EntityVehicleSVA v, long now, boolean silent) {
        XpdrMath.Xpdr x = v.state.xpdr;
        XpdrMath.releaseHeld(x, now);                               // review 2026-10-27: a panel's code that waited its second
        List<EntityPlayer> pilots = cardHolders(v);
        k.pilot = pilots.isEmpty() ? "" : pilots.get(0).getCommandSenderName();
        if (!xpdrOn(v)) {                                           // audit 2026-10-22: no power, a wreck - no answer
            k.replied = false;
            k.mode = XpdrMath.fitted(v.getVehicleType()) ? x.mode : XpdrMath.OFF;
            k.code = -1;
            k.fromCard = false;
            k.cardOwner = "";
            k.cardGen = 0;
            k.cardFirst = k.cardUntil = -1;
            return;
        }
        if (!silent) {
            x.lastInterrogated = now;
        }
        k.replied = true;
        k.mode = x.mode;
        k.code = x.code;
        k.fromCard = x.fromCard();
        k.cardOwner = x.cardOwner;
        k.cardGen = x.cardGen;
        k.cardFirst = x.cardFirst;
        k.cardUntil = x.cardUntil;
        k.codeSince = x.codeSince;
        k.ident = x.identOn(now);
        k.board = XpdrMath.givesBoard(x.mode) ? v.getBoardNumber() : 0;
        // liner wave 7: the liner's mode C altitude from its selected ADR - none, no altitude (the track form shows none);
        // audit 2026-10-22: from the aircraft's centre, as the radars measure it (from its belly the track jumped between the two)
        if (XpdrMath.givesAltitude(x.mode) && !k.altKnown && v.xpdrAltitude()) {
            k.y = centre(v)[1];
            k.altKnown = true;
            k.altAt = now;
        }
    }

    // ------------------------------------------------------------------ audit 2026-10-22: a launcher without its post

    /**
     * What an owner's command posts trust, for a launcher whose post is off or unloaded (bug 2): from the linked post's trust
     * data when it has one, else every post of the owner in the dimension together. Today's code from the owner's secret
     * (any post with the day key on).
     */
    public static final class Trust {
        public int dayCode = -1;
        public final List<AirDefenseDataSVA.KpInfo> infos = new ArrayList<AirDefenseDataSVA.KpInfo>();
        public final List<String> trusted = new ArrayList<String>();
        /**
         * Code-only wave (item 15): the LINKED post's trust data when it identifies by the code now (codeOnlyPost) - never
         * the owner's other posts: an unlinked launcher works the usual way; null: the usual mode.
         */
        public AirDefenseDataSVA.KpInfo codeOnly;

        public boolean ownCode(int code) {
            for (AirDefenseDataSVA.KpInfo i : infos) {
                if (i.ownCode(code)) {
                    return true;
                }
            }
            return false;
        }

        public boolean whitelisted(int board, String owner) {
            for (AirDefenseDataSVA.KpInfo i : infos) {
                if (i.whitelisted(board, owner)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * «Свой на 10 минут», or 7600 «свой» while it squawks 7600 (audit review 2026-10-22: as on the post - before, a launcher
         * whose post was off kept it a friend under any code it switched to, into a forbidden zone too).
         */
        public boolean granted(String vid, boolean noRadio, long now) {
            for (AirDefenseDataSVA.KpInfo i : infos) {
                NoRadioMath.Entry e = i.noRadio.get(vid);
                if (i.granted(vid, now) || noRadio && e != null && e.decision == NoRadioMath.D_FRIEND && now < e.until) {
                    return true;
                }
            }
            return false;
        }

        /** «Только вести», or 7600 «проверить» while it squawks 7600. */
        public boolean held(String vid, boolean noRadio, long now) {
            for (AirDefenseDataSVA.KpInfo i : infos) {
                NoRadioMath.Entry e = i.noRadio.get(vid);
                if (i.held(vid, now) || noRadio && e != null && e.decision == NoRadioMath.D_CHECK) {
                    return true;
                }
            }
            return false;
        }

        /** A post called its 7600 hostile: no grace. */
        public boolean hostile(String vid) {
            for (AirDefenseDataSVA.KpInfo i : infos) {
                NoRadioMath.Entry e = i.noRadio.get(vid);
                if (e != null && e.decision == NoRadioMath.D_HOSTILE) {
                    return true;
                }
            }
            return false;
        }
    }

    public Trust trust(World w, String owner, String link) {
        Trust t = new Trust();
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        if (d == null) {
            return t;
        }
        AirDefenseDataSVA.KpInfo li = link == null || link.length() == 0 ? null : d.info(link, false);
        if (li != null && li.owner.equalsIgnoreCase(owner)) {
            t.infos.add(li);
        } else {
            t.infos.addAll(d.infosOf(owner, w.provider.dimensionId).values());
        }
        boolean key = false;
        for (AirDefenseDataSVA.KpInfo i : t.infos) {
            key |= i.dayKey;
            for (String s : i.trusted) {
                if (!t.trusted.contains(s)) {
                    t.trusted.add(s);
                }
            }
        }
        String sec = d.secret(owner);
        if (key && ConfigSVA.adDayKey && sec.length() > 0) {
            t.dayCode = d.keyCode(owner, AirDefenseDataSVA.keyPeriod(w));      // code-only wave: the key's clock, pepper, generation
        }
        t.codeOnly = codeOnlyPost(w, owner, link, w.getTotalWorldTime());
        return t;
    }

    // ------------------------------------------------------------------ code-only wave: «Опознавание: ПО КОДУ»

    /**
     * Code-only wave (item 15): the trust data of the post a launcher / radar is LINKED to when that post identifies by the
     * code now - working, unpowered or unloaded (its KpInfo); null: the usual mode (no link, another owner's post, a broken
     * one - its data went with it, the mode off / arming / forbidden by the server / no day key). In doubt - less fire.
     */
    public static AirDefenseDataSVA.KpInfo codeOnlyPost(World w, String owner, String link, long now) {
        AirDefenseDataSVA d = link == null || link.length() == 0 ? null : AirDefenseDataSVA.get(w);
        AirDefenseDataSVA.KpInfo i = d == null ? null : d.info(link, false);
        return i != null && i.owner.equalsIgnoreCase(owner == null ? "" : owner) && i.codeOnlyActive(now, ConfigSVA.adCodeOnly, ConfigSVA.adDayKey) ? i : null;
    }

    /**
     * Code-only wave: one air defence owner's key as a post (or a launcher linked to it) classes a round by it - the key's
     * code of this period and of the last one (the overlap window, item 18), the generation the cards must carry, the post's
     * options (a code dialled by hand - 3б, on by default; the guessing guard with 3а / 3б).
     */
    public static final class CodeKey {
        public String owner = "";
        public int opts = CodeOnlyMath.DEFAULT_OPTS, gen = 1, keyNow = -1, keyPrev = -1;
        public long period;
        public boolean overlap, manual, guard;
    }

    /** The key of a post's trust data now (its owner's secret, the server's pepper, his generation; the post's options). */
    public static CodeKey codeKey(World w, AirDefenseDataSVA.KpInfo i) {
        CodeKey k = new CodeKey();
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        if (i == null || d == null) {
            return k;
        }
        k.owner = i.owner;
        k.opts = CodeOnlyMath.opts(i.coOpts);
        k.gen = d.gen(i.owner);
        k.period = AirDefenseDataSVA.keyPeriod(w);
        k.overlap = AirDefenseDataSVA.keyOverlap(w);
        if (i.dayKey && ConfigSVA.adDayKey) {
            k.keyNow = d.keyCode(i.owner, k.period);
            k.keyPrev = k.period > 0 ? d.keyCode(i.owner, k.period - 1) : -1;
        }
        k.manual = CodeOnlyMath.manualCounts(k.opts, ConfigSVA.adCodeOnlyManual);
        k.guard = CodeOnlyMath.probeGuard(k.opts);
        return k;
    }

    /** classifyByCode's answer with this bit: the «ПОДБОР» block began with this round (the post's log). */
    public static final int PROBE_BEGAN = 1 << 24;

    /**
     * Code-only wave (items 1-11, 22, 31): one contact classed by the transponder's code alone (RoeMath.classifyByCode) - the
     * command post's tracks and a linked launcher's own eyes alike. Sets the contact's class, reason and miss, its 7700 state
     * (the grace per vehicle shared by all the owner's posts - AirDefenseDataSVA.emergency), counts a wrong code that stood 3 s
     * towards the «ПОДБОР» block (shared per owner, by the aircraft and by the pilot; never 7000 / special / the last
     * period's / a tower's code) and lights the transponder's ОПОЗН lamp (CodeOnlyMath.caution).
     * @param asked interrogated at all (a post's track: bug 15 - UNKNOWN until then)
     * @param at the interrogation's tick (the post: the track's askedAt - the same answer counted twice is one miss; a
     * launcher's eyes: now, the second miss by the clock)
     * @param granted «свой на 10 минут» or 7600 «свой» (the caller's trust data)
     * @param noRadioGrace 7600 in its grace (the post's episodes / the launchers' 2 minutes)
     * @param ownCode one of the post's own codes (counts only with option 3а)
     * @param airportCode a friendly airport's code as the usual check allows it (no violation, no handoff; counts only with 3в)
     * @param violator in a forbidden zone or handed over by a tower
     * @return RoeMath's packed verdict (| PROBE_BEGAN)
     */
    public int classifyByCode(World w, CodeKey k, Contact c, boolean asked, long at, boolean granted, boolean noRadioGrace, boolean ownCode,
            boolean airportCode, boolean violator, long now) {
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        boolean em = c.replied && c.code == XpdrMath.EMERGENCY;
        int grace = Math.max(0, ConfigSVA.adCodeOnlyEmergencySeconds) * 20;
        NoRadioMath.Entry e = d == null || !asked ? null : d.emergency(k.owner, c.vid, em, at, now);  // the answer's tick: a stale one changes nothing
        c.em = em ? e == null ? NoRadioMath.S_PENDING : CodeOnlyMath.emergencyState(e, now, grace) : -1;
        c.emLeft = em ? e == null ? -1 : CodeOnlyMath.emergencyLeft(e, now, grace) : 0;
        boolean blocked = k.guard && d != null && d.probeBlocked(k.owner, c.vid, c.pilot, now);
        int card = CodeOnlyMath.cardState(c.cardOwner, c.cardGen, c.cardFirst, c.cardUntil, k.owner, k.gen, k.period, k.overlap);
        // review 2026-10-27: a code dialled by hand (the key's, an own one) counts once it stood 3 s at THIS interrogation
        boolean stood = CodeOnlyMath.stood(c.codeSince, at);
        int id = CodeOnlyMath.identify(c.replied, c.code, k.keyNow, k.keyPrev, k.overlap, card, c.fromCard, k.manual, stood,
                (k.opts & CodeOnlyMath.OPT_OWN_CODES) != 0 && ownCode, (k.opts & CodeOnlyMath.OPT_AIRPORT) != 0 && airportCode);
        int gap = Math.max(0, ConfigSVA.adCodeOnlyConfirmSeconds) * 20;
        boolean began = false;
        // item 11: a wrong code that stood 3 s counts towards the block (by the aircraft and by its pilot, per owner); review
        // 2026-10-27: 3 s at the interrogation (`at`), not at the post's round, and each interrogation's code once
        if (asked && k.guard && !blocked && d != null && c.replied && CodeOnlyMath.guessed(id) && c.code != k.keyNow && at != c.probeAt
                && CodeOnlyMath.probeCounts(c.code, c.codeSince, at, k.keyPrev, com.sva.block.AtcServerSVA.INSTANCE.issuedAny(c.code, c.id, c.vid))) {
            c.probeAt = at;
            began = d.probeWrong(k.owner, c.vid, c.pilot, c.code, now, ConfigSVA.adCodeOnlyProbeCodes, Math.max(1, ConfigSVA.adCodeOnlyProbeMinutes) * 1200L);
            blocked = began;
        }
        int r = RoeMath.classifyByCode(asked, granted, blocked, c.replied, c.code, c.em, noRadioGrace, id, violator, c.confirm, at, gap);
        c.codeOnly = true;
        c.cls = RoeMath.idClass(r);
        c.idr = RoeMath.idReason(r);
        c.idMiss = RoeMath.idMissOf(r);
        if (c.v != null && CodeOnlyMath.caution(asked, c.cls, id)) {
            c.v.state.xpdr.caution(at);                             // item 22: the ОПОЗН lamp (lit from that interrogation) before any message
        } else if (c.v != null && asked) {
            c.v.state.xpdr.identified(at);                          // a newer match puts out a lamp an older answer lit
        }
        return began ? r | PROBE_BEGAN : r;
    }

    /** acceptedBeforeFire's answer: fire; a match (the engagement goes); 7700 in its grace (the engagement pauses - review 2026-10-27). */
    public static final int ACC_FIRE = 0, ACC_MATCH = 1, ACC_HOLD = 2;

    /**
     * Code-only wave (item 14): a launcher's silent look at its target's transponder right before a burst / launch (the ОТВЕТ
     * lamp stays dark) - the key's code matches now (a card taken, the key's code dialled with «Ручной набор» that stood 3 s,
     * an own code with 3а) or it squawks 7700 within its grace: no fire, the track takes the fresh answer (the post's next
     * round sees a friend). Never past the «ПОДБОР» block. A missile already in the air is not called back.
     * <p>
     * Review 2026-10-27: the 7700 seen here is charged to the vehicle's grace (the episode opened or run on - it was only read,
     * and a missing memory let it go free), the misses stay and the launcher pauses the engagement instead of dropping it
     * (ACC_HOLD): a client showing 7700 only to this look gained a fresh warning sequence for nothing. A code dialled by hand
     * matches here only once it stood 3 s, and a wrong one that stood counts as a guess here too.
     */
    public int acceptedBeforeFire(World w, CodeKey k, Contact c, AirDefenseDataSVA.KpInfo i, long now) {
        if (c.v == null || c.v.isDead) {
            return ACC_FIRE;
        }
        interrogate(c, c.v, now, true);
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        if (!c.replied || d == null || k.guard && d.probeBlocked(k.owner, c.vid, c.pilot, now)) {
            return ACC_FIRE;
        }
        int card = CodeOnlyMath.cardState(c.cardOwner, c.cardGen, c.cardFirst, c.cardUntil, k.owner, k.gen, k.period, k.overlap);
        int id = CodeOnlyMath.identify(true, c.code, k.keyNow, k.keyPrev, k.overlap, card, c.fromCard, k.manual, CodeOnlyMath.stood(c.codeSince, now),
                (k.opts & CodeOnlyMath.OPT_OWN_CODES) != 0 && i != null && i.ownCode(c.code), false);
        // review 2026-10-27: this look is an interrogation too - a wrong code that stood 3 s counts as a guess here as well (the
        // post's survey hears one code in 6 s: codes held a few seconds each were tried at the shot and never counted)
        if (k.guard && CodeOnlyMath.guessed(id) && c.code != k.keyNow && now != c.probeAt
                && CodeOnlyMath.probeCounts(c.code, c.codeSince, now, k.keyPrev, com.sva.block.AtcServerSVA.INSTANCE.issuedAny(c.code, c.id, c.vid))) {
            c.probeAt = now;
            if (d.probeWrong(k.owner, c.vid, c.pilot, c.code, now, ConfigSVA.adCodeOnlyProbeCodes, Math.max(1, ConfigSVA.adCodeOnlyProbeMinutes) * 1200L)) {
                return ACC_FIRE;                                    // the «ПОДБОР» block began with this look
            }
        }
        int st = -1;
        if (c.code == XpdrMath.EMERGENCY) {
            NoRadioMath.Entry e = d.emergency(k.owner, c.vid, true, now);
            st = CodeOnlyMath.emergencyState(e, now, Math.max(0, ConfigSVA.adCodeOnlyEmergencySeconds) * 20);
        }
        if (RoeMath.idMatch(id) || NoRadioMath.friend(st)) {
            c.askedAt = Math.max(c.askedAt, now);                   // the post's merge keeps this answer over the radar's older one
            CodeOnlyMath.hit(c.confirm);
            c.cls = RoeMath.FRIEND;                                 // till the post's next round: not taken again meanwhile
            c.idr = RoeMath.idMatch(id) ? id : RoeMath.ID_GRANTED;
            c.idMiss = RoeMath.ID_NONE;
            return ACC_MATCH;
        }
        if (st >= 0 && NoRadioMath.holdFire(st)) {
            c.askedAt = Math.max(c.askedAt, now);
            c.cls = RoeMath.EMERGENCY;                              // the misses stay: the grace holds the fire, it clears nothing
            c.idr = RoeMath.ID_EMERGENCY;
            c.idMiss = RoeMath.ID_NONE;
            return ACC_HOLD;
        }
        return ACC_FIRE;
    }

    /** Code-only wave (item 22): the card holders seen aboard each vehicle (entity id -> «name,name,»): a new one is checked once. */
    private final Map<Integer, String> boarded = new HashMap<Integer, String>();

    /** Every card holder who was not aboard at the last look is checked (noCardPost) and told. */
    private void boarding(EntityVehicleSVA v, List<EntityPlayer> holders) {
        StringBuilder sig = new StringBuilder(",");
        for (EntityPlayer p : holders) {
            sig.append(p.getCommandSenderName()).append(',');
        }
        String was = boarded.put(Integer.valueOf(v.getEntityId()), sig.toString());
        for (EntityPlayer p : holders) {
            if (p instanceof EntityPlayerMP && (was == null || !was.contains("," + p.getCommandSenderName() + ","))) {
                String[] n = boardingNote(v.worldObj, p, v.state.xpdr);
                if (n != null) {
                    tell((EntityPlayerMP) p, 0xF0B040, n[0], (Object[]) java.util.Arrays.copyOfRange(n, 1, n.length));
                }
            }
        }
    }

    /** Review 2026-10-27: the boarding notes' lang keys by CodeOnlyMath.B_* (B_NONE: none). */
    private static final String[] BOARDING_KEYS = {null, "sva.ad.msg.noCard", "sva.ad.msg.coKeyOff", "sva.ad.msg.coOtherCard"};

    /**
     * Code-only wave (item 22): the player boarding is the owner of a code-only post in this dimension (switched on - arming
     * too, the server allowing it; review 2026-10-27, batch 3: with the day key) or on its access list, and he carries no card of that owner's key good now - the post's
     * name («Нет карты кодов: КП ... примет борт за чужого»); null - nothing to say. The rider himself is checked, not the
     * vehicle's owner.
     */
    public String noCardPost(World w, EntityPlayer p) {
        String[] n = boardingNote(w, p, null);
        return n != null && BOARDING_KEYS[CodeOnlyMath.B_NO_CARD].equals(n[0]) ? n[1] : null;
    }

    /**
     * Review 2026-10-27 (cards of two owners): what the boarding pilot is told for the first listed code-only post that will
     * take him for a stranger (CodeOnlyMath.boardingNote) - {lang key, post's name[, the card's owner]}; null nothing. `x`
     * the transponder after this second's card round (null: only «Нет карты кодов»).
     */
    public String[] boardingNote(World w, EntityPlayer p, XpdrMath.Xpdr x) {
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        if (d == null || p == null || !ConfigSVA.adCodeOnly) {
            return null;
        }
        long period = AirDefenseDataSVA.keyPeriod(w), today = XpdrMath.day(w.getWorldTime());
        boolean overlap = AirDefenseDataSVA.keyOverlap(w);
        String name = p.getCommandSenderName();
        int keyed = 0;
        java.util.Set<String> owners = new java.util.HashSet<String>();
        for (ItemStack st : p.inventory.mainInventory) {
            if (ItemCodeCardSVA.keyed(st) && cardOffer(d, st, period, today, overlap)[0] >= 2 && owners.add(ItemCodeCardSVA.owner(st).toLowerCase())) {
                keyed++;                                            // good keyed cards of different owners
            }
        }
        String keyOwner = x != null && x.fromCard() ? x.cardOwner : "";
        for (AirDefenseDataSVA.KpInfo i : d.infosIn(w.provider.dimensionId)) {
            boolean card = owners.contains(i.owner.toLowerCase());
            // review 2026-10-27 (batch 3): a post without the day key (or the server's) classes the usual way - nothing to say
            int b = CodeOnlyMath.boardingNote(listed(i, name), i.codeOnlyMarked(true, ConfigSVA.adDayKey), card, x == null || x.dayKey, x == null ? 0 : keyed,
                    i.owner, keyOwner);
            if (b != CodeOnlyMath.B_NONE) {
                return b == CodeOnlyMath.B_OTHER_CARD ? new String[] {BOARDING_KEYS[b], i.name, keyOwner.length() > 0 ? keyOwner : "-"}
                        : new String[] {BOARDING_KEYS[b], i.name};
            }
        }
        return null;
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

    /** A projectile's tick: still FLYING, or what ended it. */
    public static final int FLYING = 0, HIT = 1, STOPPED = 2, EXPIRED = 3;

    private void moveShots() {
        for (Iterator<Projectile> it = shots.iterator(); it.hasNext();) {
            Projectile p = it.next();
            WorldServer w = DimensionManager.getWorld(p.dim);
            if (w == null) {
                it.remove();
                continue;
            }
            if (step(w, p) != FLYING) {
                it.remove();
            }
        }
    }

    /**
     * One tick of a shell / missile: the missile's seeker steers it, it moves one step, the step is walked block by block
     * (audit 2026-10-22, bug 4: BallisticsMath.firstHit with ShotProbe - before, only the step's end block was asked and
     * after the hit test, so a 4-block step jumped over a roof or a wall), the target is hit only on the part of the step
     * before the first block, else the block stops it there.
     */
    private int step(WorldServer w, Projectile p) {
        if (--p.life < 0) {
            if (p.missile) {
                burst(w, p.x, p.y, p.z, false);
            }
            return EXPIRED;
        }
        Entity te = w.getEntityByID(p.target);
        EntityVehicleSVA target = te instanceof EntityVehicleSVA && !te.isDead ? (EntityVehicleSVA) te : null;
        if (p.missile && target != null && !p.lost) {
            double[] c = centre(target);
            if (p.life % 5 == 0 && !losClear(w, p.x, p.y, p.z, c[0], c[1], c[2])) {
                p.lost = true;                                      // behind the terrain: the seeker loses it
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
        double tb = BallisticsMath.firstHit(ox, oy, oz, p.x, p.y, p.z, new ShotProbe(w, p));
        double ex = tb < 0 ? p.x : ox + (p.x - ox) * tb, ey = tb < 0 ? p.y : oy + (p.y - oy) * tb, ez = tb < 0 ? p.z : oz + (p.z - oz) * tb;
        if (target != null && !p.harmless) {
            double[] c = centre(target);
            double r = p.missile ? BallisticsMath.MISSILE_FUSE + Math.max(target.width, target.height) / 2
                    : Math.max(target.width, target.height) / 2 + 0.6;
            if (BallisticsMath.segToPoint2(ox, oy, oz, ex, ey, ez, c[0], c[1], c[2]) <= r * r) {
                hit(w, p, target);
                return HIT;
            }
        }
        if (tb >= 0) {
            p.x = ex;
            p.y = ey;
            p.z = ez;
            if (p.missile) {
                burst(w, ex, ey, ez, false);
            } else {
                w.func_147487_a("smoke", ex, ey, ez, 2, 0.1, 0.1, 0.1, 0.01);
            }
            return STOPPED;
        }
        if (p.missile) {
            w.func_147487_a("smoke", p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.0);
            w.func_147487_a("flame", ox, oy, oz, 1, 0.0, 0.0, 0.0, 0.0);
        } else if (p.life % 2 == 0) {
            w.func_147487_a("fireworksSpark", p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
        return FLYING;
    }

    /**
     * World tests (audit 2026-10-22, bug 4): one projectile flown on its own (not in the shared list) up to `maxTicks`.
     * @return how it ended (FLYING: still in the air)
     */
    public int debugFly(WorldServer w, Projectile p, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            int r = step(w, p);
            if (r != FLYING) {
                return r;
            }
        }
        return FLYING;
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

    /** Audit 2026-10-22: forgets a `dedupe` message to this vehicle (an engagement started over: its warnings go again). */
    public void unsay(EntityVehicleSVA v, String dedupe) {
        said.remove(v.getEntityId() + "|" + dedupe);
    }

    /** World tests (audit 2026-10-22): did a `dedupe` message reach this vehicle (its record kept)? */
    public boolean debugSaid(EntityVehicleSVA v, String dedupe) {
        return said.containsKey(v.getEntityId() + "|" + dedupe);
    }

    /** One player: the chat line and the HUD line. */
    public void tell(EntityPlayerMP p, int colour, String key, Object... args) {
        ChatComponentTranslation c = new ChatComponentTranslation(key, chatArgs(args));
        c.getChatStyle().setColor(colour == 0xF05050 ? EnumChatFormatting.RED : colour == 0x62E06A ? EnumChatFormatting.GREEN : EnumChatFormatting.GOLD);
        p.addChatComponentMessage(c);
        NBTTagCompound t = new NBTTagCompound();
        t.setString("K", key);
        NBTTagList a = new NBTTagList();
        for (Object o : args) {
            a.appendTag(new net.minecraft.nbt.NBTTagString(hudArg(o)));
        }
        t.setTag("A", a);
        t.setInteger("C", colour);
        NetADSVA.send(p, NetADSVA.D_MSG, t);
    }

    /**
     * Audit review 2026-10-22: a lang key passed as an argument of tell / tellPilot (the IFF call's reason) - translated in the
     * chat line and on the HUD. Only this marks a key: the «#»-prefixed strings it replaced were guessed from any argument,
     * and the post's name, a zone's, the operator's message are the players' own text - a post named «#chat.type.text» put
     * a translation without its arguments into every pilot's chat it warned, and the client's chat threw on it (a crash,
     * again on every return).
     */
    public static final class LangKey {
        public final String key;

        public LangKey(String key) {
            this.key = key == null ? "" : key;
        }

        @Override
        public String toString() {
            return "#" + key;
        }
    }

    /**
     * Audit 2026-10-22 (7.3): a lang-key argument goes into the chat line as a translation of its own (the chat showed the
     * raw «#sva.iff.reason.noReply») - audit review: only a LangKey, any string stays text as it is.
     */
    static Object[] chatArgs(Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object o = args[i];
            out[i] = o instanceof LangKey ? new ChatComponentTranslation(((LangKey) o).key) : o;
        }
        return out;
    }

    /**
     * An argument as the HUD line gets it (AirDefenseClientSVA: «#key» translated, «##» a text that began with «#»): audit
     * review - a player's «#...» no longer passes for a key there either.
     */
    public static String hudArg(Object o) {
        if (o instanceof LangKey) {
            return o.toString();
        }
        String s = String.valueOf(o);
        return s.startsWith("#") ? "#" + s : s;
    }

    /** World tests (audit 2026-10-22): the chat line's arguments as tell() builds them. */
    public static Object[] debugChatArgs(Object... args) {
        return chatArgs(args);
    }

    /** The HUD line's argument back from hudArg: a key (translated by the caller) - null, else the text. */
    public static String hudText(String s) {
        if (s.startsWith("##")) {
            return s.substring(1);
        }
        return s.startsWith("#") && s.length() > 1 ? null : s;
    }

    /** Audit 2026-10-22: the launch log's lines waiting for the writer, at most this many (a flood is cut, said once). */
    public static final int LOG_QUEUE = 2048;
    private final List<String> logQueue = new ArrayList<String>();
    private final Object logFile = new Object();
    private java.util.concurrent.ExecutorService logWriter;
    private int logDropped;

    /**
     * logs/airdefense.log (airdefense.launchLog) and the server log. Audit 2026-10-22: the line is stamped now and queued;
     * the queue is written once a second in one go on a writer thread (flushLaunchLog) - the server thread no longer opens
     * and closes the file for every line.
     */
    public void launchLog(String line) {
        System.out.println("[SVA-AD] " + line);
        if (!ConfigSVA.adLaunchLog) {
            return;
        }
        String stamped = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + " " + line;
        synchronized (logQueue) {
            if (logQueue.size() < LOG_QUEUE) {
                logQueue.add(stamped);
            } else {
                logDropped++;
            }
        }
    }

    /** The launch log's file. */
    public static File launchLogFile() {
        MinecraftServer s = MinecraftServer.getServer();
        return s == null ? new File("logs", "airdefense.log") : s.getFile("logs/airdefense.log");
    }

    /**
     * Writes the queued lines: on the writer thread (the tick, once a second), or here and now (`wait`: the server stopping,
     * the world tests).
     */
    public void flushLaunchLog(boolean wait) {
        final List<String> batch;
        synchronized (logQueue) {
            if (logQueue.isEmpty()) {
                return;
            }
            batch = new ArrayList<String>(logQueue);
            logQueue.clear();
            if (logDropped > 0) {
                batch.add("(" + logDropped + " lines not written - too many at once)");
                logDropped = 0;
            }
        }
        final File f = launchLogFile();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                writeLog(f, batch);
            }
        };
        if (wait) {
            r.run();
            return;
        }
        try {
            if (logWriter == null) {
                logWriter = java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable task) {
                        Thread t = new Thread(task, "SVA air defence log");
                        t.setDaemon(true);
                        return t;
                    }
                });
            }
            logWriter.execute(r);
        } catch (Exception e) {
            r.run();                                                // no thread to be had: written here
        }
    }

    private void writeLog(File f, List<String> lines) {
        synchronized (logFile) {
            Writer out = null;
            try {
                f.getParentFile().mkdirs();
                out = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
                for (String l : lines) {
                    out.write(l + "\n");
                }
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
    }

    // ------------------------------------------------------------------ the transponder

    /** IDENT pressed (the driver): the blip flashes on every radar for 18 s. */
    public void ident(EntityVehicleSVA v) {
        if (xpdrOn(v)) {
            v.state.xpdr.identUntil = v.worldObj.getTotalWorldTime() + XpdrMath.IDENT_TICKS;
        }
    }

    /** The transponder panel's settings (the driver): every field (an old client's packet). */
    public void setXpdr(EntityVehicleSVA v, int mode, int code, boolean dayKey) {
        setXpdr(v, XpdrMath.CH_ALL, mode, code, dayKey);
    }

    /**
     * Audit 2026-10-22 (bug 12): only the fields the pilot changed (XpdrMath.CH_* bits) - the card's code, a tower's code or
     * a 7600 set from the post are no longer put back by a panel's stale copy (XpdrMath.applyPanel).
     */
    public void setXpdr(EntityVehicleSVA v, int changed, int mode, int code, boolean dayKey) {
        XpdrMath.applyPanel(v.state.xpdr, changed, mode, code, dayKey, v.worldObj.getTotalWorldTime());
    }

    /**
     * Who works a vehicle's transponder: its driver; the liner's - either pilot (audit 2026-10-22, 7.3: the panel is on the
     * pedestal between them; IDENT from the F/O's seat was refused).
     */
    public static boolean xpdrOperator(EntityVehicleSVA v, EntityPlayer p) {
        if (v == null || p == null) {
            return false;
        }
        if (v.riddenByEntity == p) {
            return true;
        }
        return v.getVehicleType() == com.sva.vehicle.VehicleType.LINER && com.sva.entity.LinerLogicSVA.pilot(v, p);
    }

    /**
     * Who may hold the code card that works a vehicle's transponder: its driver; the liner's - either pilot (code-only wave,
     * item 20: the F/O flying alone had a card that did nothing). Passengers and the cargo never count.
     */
    public static List<EntityPlayer> cardHolders(EntityVehicleSVA v) {
        List<EntityPlayer> out = new ArrayList<EntityPlayer>();
        if (v.riddenByEntity instanceof EntityPlayer) {
            out.add((EntityPlayer) v.riddenByEntity);
        }
        if (v.getVehicleType() == com.sva.vehicle.VehicleType.LINER) {
            for (Entity e : v.riders()) {
                if (e instanceof EntityPlayer && !out.contains(e) && xpdrOperator(v, (EntityPlayer) e)) {
                    out.add((EntityPlayer) e);
                }
            }
        }
        return out;
    }

    /**
     * Code-only wave: what one card gives now - {rank, code}: rank 3 a card of a key good this period, 2 one good only for the
     * last period's code (the overlap window), 0 nothing (blank, another generation - «Сменить ключ», past its periods, its
     * owner without a secret, an older card).
     * <p>
     * Review 2026-10-27 (batch 2): an older card (it carries its code) gives nothing any more - every post's day code is the
     * key's now (the server's pepper, the generation: keyCode), so its code never matched any post, yet it lit КЛЮЧ ДНЯ (a
     * tower refused the aircraft an airport code for it) and its tooltip said «Код на сегодня»; on a doDaylightCycle=false
     * server for ever. `today` is no longer read (kept for the callers).
     */
    public static int[] cardOffer(AirDefenseDataSVA d, ItemStack s, long period, long today, boolean overlap) {
        if (ItemCodeCardSVA.keyed(s) && d != null) {
            String own = ItemCodeCardSVA.owner(s);
            int st = CodeOnlyMath.cardState(own, ItemCodeCardSVA.gen(s), ItemCodeCardSVA.first(s), ItemCodeCardSVA.until(s), own, d.gen(own), period,
                    overlap);
            int code = CodeOnlyMath.cardGood(st) ? d.keyCode(own, st == CodeOnlyMath.CARD_OK ? period : period - 1) : -1;
            return code < 0 ? new int[] {0, -1} : new int[] {st == CodeOnlyMath.CARD_OK ? 3 : 2, code};
        }
        return new int[] {0, -1};
    }

    /** World tests / the screens: the code a card gives on this world now (-1 none). */
    public static int cardCodeNow(World w, ItemStack s) {
        return cardOffer(AirDefenseDataSVA.get(w), s, AirDefenseDataSVA.keyPeriod(w), XpdrMath.day(w.getWorldTime()), AirDefenseDataSVA.keyOverlap(w))[1];
    }

    /**
     * «Ключ дня»: the pilots' code cards set the code (every second). Code-only wave (items 17-21, 30): a card of a key gives
     * the code the server works out for this key period (the next period's by itself; the last period's within the overlap
     * window) - the best of all the pilots' cards. No good card any more - the card's code goes back to 7000 (a hijacker
     * without the card loses the key within a second; a code the card left behind is no code dialled by hand); a code the
     * pilot dialled himself (the day key off) is never touched. An older card (its own code) gives nothing (review batch 2).
     * <p>
     * Review 2026-10-27 (cards of two owners): the card whose key matters here wins first - its owner's switched-on code-only
     * post nearest to the vehicle, else one of his whose list has a holder (CodeOnlyMath.cardRelevance / betterCard); the
     * longest-running card used to win wherever the aircraft was, and the owner's own launchers fired on him for the ally's
     * card he also carried. Batch 2: an owner with such a post within CodeOnlyMath.CARD_REACH comes before the nearest one's
     * (a guest handed Bob's card to cross Bob's zone kept squawking his own key's wherever a third owner's post was nearer,
     * and Bob's posts made him a target), and between equal cards the first in the pilot's inventory wins (the hotbar first)
     * - the pilot chooses by where he keeps it.
     */
    private void cardCode(EntityVehicleSVA v, List<EntityPlayer> pilots, long now) {
        XpdrMath.Xpdr x = v.state.xpdr;
        if (!x.dayKey || !ConfigSVA.adDayKey) {
            return;
        }
        World w = v.worldObj;
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        long period = AirDefenseDataSVA.keyPeriod(w), today = XpdrMath.day(w.getWorldTime());
        boolean overlap = AirDefenseDataSVA.keyOverlap(w);
        String near = nearestCodeOnlyOwner(d, w.provider.dimensionId, v.posX, v.posZ);
        java.util.Set<String> reach = codeOnlyOwnersInReach(d, w.provider.dimensionId, v.posX, v.posZ);
        ItemStack best = null;
        int bestRank = 0, bestCode = -1, bestRel = 0;
        for (EntityPlayer pilot : pilots) {
            if (pilot == null || pilot.inventory == null) {
                continue;
            }
            for (ItemStack s : pilot.inventory.mainInventory) {
                int[] o = cardOffer(d, s, period, today, overlap);
                if (o[0] <= 0) {
                    continue;
                }
                String own = ItemCodeCardSVA.owner(s);
                int rel = own.length() == 0 ? 0 : CodeOnlyMath.cardRelevance(reach.contains(own.toLowerCase()), own.equalsIgnoreCase(near),
                        listedOnCodeOnly(d, w.provider.dimensionId, own, pilots));
                if (best == null || CodeOnlyMath.betterCard(rel, o[0], bestRel, bestRank)) {   // of equals the first stays: inventory order
                    best = s;
                    bestRank = o[0];
                    bestCode = o[1];
                    bestRel = rel;
                }
            }
        }
        if (bestRank == 0) {
            x.dropCard(true, now);
            return;
        }
        x.takeCard(bestCode, ItemCodeCardSVA.owner(best), ItemCodeCardSVA.gen(best), ItemCodeCardSVA.first(best), ItemCodeCardSVA.until(best),
                period, now);                                       // only a key's card ranks (an older card gives nothing)
        // item 22: 5 and 1 minutes before the card runs out - «КАРТА КОДОВ: 5 МИН» to the pilots (once each)
        int m = CodeOnlyMath.cardWarnMinutes(CodeOnlyMath.cardTicksLeft(x.cardUntil, AirDefenseDataSVA.keyClock(), w.getWorldTime(), w.getTotalWorldTime()));
        if (m > 0) {
            for (EntityPlayer p : pilots) {
                String k = v.getEntityId() + "|card" + m + "|" + p.getCommandSenderName();
                Long last = said.get(k);
                if (p instanceof EntityPlayerMP && (last == null || now - last.longValue() >= 6000 || now < last.longValue())) {
                    said.put(k, Long.valueOf(now));
                    tell((EntityPlayerMP) p, 0xF0B040, "sva.ad.msg.cardExpires", Integer.valueOf(m));
                }
            }
        }
    }

    /**
     * Review 2026-10-27 (cards of two owners): the owner of the switched-on code-only post nearest to (x, z) in a dimension
     * ("" none) - his card is the one that matters there.
     */
    public static String nearestCodeOnlyOwner(AirDefenseDataSVA d, int dim, double x, double z) {
        String best = "";
        double bd = Double.MAX_VALUE;
        if (d == null) {
            return best;
        }
        for (Map.Entry<String, AirDefenseDataSVA.KpInfo> e : d.codeOnlyPostsIn(dim).entrySet()) {
            int[] p = AirDefenseDataSVA.keyXZ(e.getKey());
            double dd = p == null ? Double.MAX_VALUE / 2 : (p[0] + 0.5 - x) * (p[0] + 0.5 - x) + (p[1] + 0.5 - z) * (p[1] + 0.5 - z);
            if (dd < bd) {
                bd = dd;
                best = e.getValue().owner;
            }
        }
        return best;
    }

    /**
     * Review 2026-10-27, batch 2: the owners (lower case) of the switched-on code-only posts in a dimension within
     * CodeOnlyMath.CARD_REACH of (x, z) - their posts see an aircraft there, so their cards come first.
     */
    public static java.util.Set<String> codeOnlyOwnersInReach(AirDefenseDataSVA d, int dim, double x, double z) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        if (d == null) {
            return out;
        }
        double r2 = CodeOnlyMath.CARD_REACH * CodeOnlyMath.CARD_REACH;
        for (Map.Entry<String, AirDefenseDataSVA.KpInfo> e : d.codeOnlyPostsIn(dim).entrySet()) {
            int[] p = AirDefenseDataSVA.keyXZ(e.getKey());
            if (p != null && (p[0] + 0.5 - x) * (p[0] + 0.5 - x) + (p[1] + 0.5 - z) * (p[1] + 0.5 - z) <= r2) {
                out.add(e.getValue().owner.toLowerCase());
            }
        }
        return out;
    }

    /** Review 2026-10-27: one of the holders is the owner or on the access list of a switched-on code-only post of `owner` here. */
    private static boolean listedOnCodeOnly(AirDefenseDataSVA d, int dim, String owner, List<EntityPlayer> holders) {
        if (d == null) {
            return false;
        }
        for (AirDefenseDataSVA.KpInfo i : d.codeOnlyPostsIn(dim).values()) {
            if (!i.owner.equalsIgnoreCase(owner)) {
                continue;
            }
            for (EntityPlayer p : holders) {
                if (p != null && listed(i, p.getCommandSenderName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The player owns this post or is on its access list. */
    private static boolean listed(AirDefenseDataSVA.KpInfo i, String name) {
        if (i.owner.equalsIgnoreCase(name)) {
            return true;
        }
        for (String a : i.access) {
            if (a.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** World tests: one round of the code cards for a vehicle (its pilots' inventories) as the tick does it. */
    public void debugCardCode(EntityVehicleSVA v) {
        cardCode(v, cardHolders(v), v.worldObj.getTotalWorldTime());
    }

    /** World tests (decision 3): the tick has checked this vehicle's boarding (item 22) - never a buggy / crawler / drill. */
    public boolean debugBoarded(EntityVehicleSVA v) {
        return boarded.containsKey(Integer.valueOf(v.getEntityId()));
    }

    /** World tests (decision 3): the tick has sent this vehicle's riders the transponder's copy (D_XPDR) - its signature, null none. */
    public String debugXpdrSent(EntityVehicleSVA v) {
        return xpdrSent.get(Integer.valueOf(v.getEntityId()));
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
            CoverageSVA.INSTANCE.tick();
            AdTerrainSVA.INSTANCE.tick(ticks);                      // audit 2026-10-22 (bug 10): the terrain memory
            if (ticks % 5 == 0) {
                vehicles(ticks % 10 == 0, ticks % 20 == 0);
            }
            if (ticks % 20 == 0) {
                syncZones(false);
                flushLaunchLog(false);
            }
            if (ticks % 200 == 0) {
                cleanup();
            }
        } catch (Throwable t) {
            System.out.println("[SVA-AD] tick: " + t);
            t.printStackTrace(System.out);
        }
    }

    /**
     * Every 5 ticks: the vehicles' motion; every 10 their riders' transponder / RWR copies; every 20 the code cards.
     * Code-only wave (decision 3): the ground vehicles' riders too when they have transponders (airdefense.allTransponders).
     */
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
                com.sva.vehicle.VehicleType vt = v.getVehicleType();
                boolean air = vt.movement.flies || vt.movement.lifts;
                // code-only wave (decision 3): a ground vehicle with its transponder - its driver's card and the panel's copy
                // too (the motion only for what a radar may see in the air, as before)
                boolean xp = XpdrMath.fitted(vt);
                if (!air && !xp) {
                    continue;
                }
                if (xp) {
                    XpdrMath.releaseHeld(v.state.xpdr, now);           // review 2026-10-27: the panel's code that waited its second
                }
                if (air) {
                    sampleMotion(v, now);
                    if (bumping(v)) {
                        bumpSeen.put(Integer.valueOf(v.getEntityId()), Long.valueOf(now));
                    }
                }
                if (sync || cards) {
                    riderRound(v, w, now, sync, cards);
                }
            }
        }
    }

    /**
     * One vehicle's riders: every 20 ticks the code cards (and the boarding check), every 10 the transponder / RWR copies.
     * Review 2026-10-27 (batch 3): a new driver / pilot (the card holders changed - a thief boarding a keyed aircraft) gets the
     * card round at once, before the panel's copy goes - the 10-tick copy between two card rounds handed him the key's code.
     */
    private void riderRound(EntityVehicleSVA v, World w, long now, boolean sync, boolean cards) {
        Integer id = Integer.valueOf(v.getEntityId());
        com.sva.vehicle.VehicleType vt = v.getVehicleType();
        boolean xp = XpdrMath.fitted(vt);
        List<EntityPlayerMP> riders = new ArrayList<EntityPlayerMP>();
        for (Entity r : v.riders()) {
            if (r instanceof EntityPlayerMP) {
                riders.add((EntityPlayerMP) r);
            }
        }
        if (riders.isEmpty()) {
            if (cards) {
                boarded.remove(id);                                 // code-only wave: the next boarding is checked again
            }
            cardWho.remove(id);
            return;
        }
        if (xp) {
            List<EntityPlayer> holders = cardHolders(v);            // code-only wave: the liner's either pilot (item 20)
            StringBuilder hs = new StringBuilder();
            for (EntityPlayer h : holders) {
                hs.append(h.getEntityId()).append(',');
            }
            String sig = hs.toString();
            if (cards || !sig.equals(cardWho.get(id))) {
                cardWho.put(id, sig);
                if (!holders.isEmpty()) {
                    cardCode(v, holders, now);
                }
            }
            if (cards && XpdrMath.seenByRadars(vt)) {
                boarding(v, holders);                               // code-only wave (item 22): «Нет карты кодов» on boarding -
            }                                                       // not in a buggy / crawler / drill: no radar ever sees them
        }
        if (sync) {
            syncRiders(v, riders, w, now);
        }
    }

    /** World tests (review batch 3): one round of a vehicle's riders as the tick does it (sync - the 10-tick copies, cards - the 20-tick cards). */
    public void debugRiderRound(EntityVehicleSVA v, boolean sync, boolean cards) {
        riderRound(v, v.worldObj, v.worldObj.getTotalWorldTime(), sync, cards);
    }

    private void syncRiders(EntityVehicleSVA v, List<EntityPlayerMP> riders, World w, long now) {
        Integer id = Integer.valueOf(v.getEntityId());
        StringBuilder who = new StringBuilder();
        for (EntityPlayerMP p : riders) {
            who.append(p.getEntityId()).append(',');
        }
        if (XpdrMath.fitted(v.getVehicleType())) {
            XpdrMath.Xpdr x = v.state.xpdr;
            long today = AirDefenseDataSVA.keyPeriod(w);             // code-only wave: the key's clock (the КЛЮЧ ДНЯ lamp)
            // code-only wave (items 22, 42): the ОПОЗН lamp and the card's minutes left go with the panel's copy (review batch 3:
            // the real minutes while 5 or less - the HUD read «5 МИН» with 1:40 left)
            boolean caution = x.idCaution(now);
            int cardWarn = x.fromCard() && x.cardOwner.length() > 0 ? CodeOnlyMath.cardMinutesLeft(CodeOnlyMath.cardTicksLeft(x.cardUntil,
                    AirDefenseDataSVA.keyClock(), w.getWorldTime(), w.getTotalWorldTime())) : 0;
            // review 2026-10-27 (cards of two owners): whose key's card sets the code now ("" none) - the panel shows it
            String keyOwner = x.fromCard() ? x.cardOwner : "";
            String sig = x.mode + ":" + x.code + ":" + x.dayKey + ":" + x.identOn(now) + ":" + x.keyLamp(today) + ":" + x.replyLamp(now) + ":" + caution
                    + ":" + cardWarn + ":" + keyOwner + ":" + who;
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
                t.setBoolean("O", caution);
                t.setByte("W", (byte) cardWarn);
                t.setString("KO", keyOwner);
                // review 2026-10-27 (batch 3): the code itself only to who works the transponder - a passenger (an airline's,
                // a lift's) left knowing the key's code, and a code dialled by hand counts by default; the public ones
                // (7000 / 7500 / 7600 / 7700) go to all (XpdrMath.riderCode; the screens show «----»)
                NBTTagCompound hidden = null;
                for (EntityPlayerMP p : riders) {
                    boolean op = xpdrOperator(v, p);
                    if (!op && hidden == null) {
                        hidden = (NBTTagCompound) t.copy();
                        hidden.setInteger("C", XpdrMath.riderCode(false, x.code));
                    }
                    NetADSVA.send(p, NetADSVA.D_XPDR, op ? t : hidden);
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
        MinecraftServer s0 = MinecraftServer.getServer();
        for (Iterator<Map.Entry<Integer, List<RadarMath.Paint>>> it = paint.entrySet().iterator(); it.hasNext();) {
            List<RadarMath.Paint> l = it.next().getValue();
            long newest = Long.MIN_VALUE;
            for (RadarMath.Paint t : l) {
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
        if (bumpSeen.size() > 256) {
            bumpSeen.clear();
        }
        if (boarded.size() > 256) {
            boarded.clear();                                        // code-only wave: at worst a pilot is told once more
        }
        if (cardWho.size() > 256) {
            cardWho.clear();                                        // review batch 3: at worst one card round more
        }
        // audit 2026-10-22: the trust data's expired decisions, handoffs and 7600 memories
        if (s0 != null && s0.worldServers != null && s0.worldServers.length > 0 && s0.worldServers[0] != null) {
            AirDefenseDataSVA d = AirDefenseDataSVA.get(s0.worldServers[0]);
            if (d != null) {
                d.prune(s0.worldServers[0].getTotalWorldTime(), noRadioMemory());
            }
        }
    }

    /** Audit 2026-10-22 (bug 8): how long a 7600 memory outlives its last episode (ticks). */
    public static long noRadioMemory() {
        return Math.max(1, ConfigSVA.adNoRadioMemoryMinutes) * 1200L;
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

    /** World tests: the zones every client gets (the code-only mark included). */
    public static NBTTagCompound debugZonesTag(AirDefenseDataSVA d) {
        return zonesTag(d);
    }

    private static NBTTagCompound zonesTag(AirDefenseDataSVA d) {
        NBTTagCompound t = new NBTTagCompound();
        NBTTagList l = new NBTTagList();
        if (ConfigSVA.adEnabled) {
            for (Map.Entry<String, List<ZoneMath.Zone>> e : d.zonesByPost().entrySet()) {
                AirDefenseDataSVA.KpInfo i = d.info(e.getKey(), false);
                // code-only wave (item 22): «ПО КОДУ»; review 2026-10-27 (batch 3): not while the post classes the usual way
                // (forbidden by the server, no day key - the post's or the server's)
                boolean co = i != null && i.codeOnlyMarked(ConfigSVA.adCodeOnly, ConfigSVA.adDayKey);
                for (ZoneMath.Zone z : e.getValue()) {
                    if (z.show) {
                        l.appendTag(publicZone(z, co));
                    }
                }
            }
        }
        t.setTag("Z", l);
        return t;
    }

    /**
     * One zone as every client gets it. Audit review 2026-10-22: without its range of codes - a post's aerodrome range is a
     * code its owner gives his friends (the post takes it as a friendly airport's), and every player had it; the pilots'
     * screens never used it (the post's own screen gets its zones whole).
     */
    public static NBTTagCompound publicZone(ZoneMath.Zone z) {
        return publicZone(z, false);
    }

    /**
     * Code-only wave (item 22): a code-only post's zone carries «CO» - the ND and the zone map sign it «ПО КОДУ» (no circle
     * round the post itself: its place is not given away by this).
     */
    public static NBTTagCompound publicZone(ZoneMath.Zone z, boolean codeOnly) {
        NBTTagCompound c = new NBTTagCompound();
        z.write(c);
        c.removeTag("CodeLo");
        c.removeTag("CodeHi");
        if (codeOnly) {
            c.setBoolean("CO", true);
        }
        return c;
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
        bumpSeen.clear();
        cmdRate.clear();
        boarded.clear();
        cardWho.clear();
        CoverageSVA.INSTANCE.clear();
        AdTerrainSVA.INSTANCE.clear();
        zonesSent = -1;
        flushLaunchLog(true);                                       // the last lines before the server goes
        if (logWriter != null) {
            logWriter.shutdown();
            logWriter = null;
        }
    }

    /**
     * Audit 2026-10-22: may this player's command post command `op` run now (CmdRateMath)? Refused: a chat line once a
     * second at most, nothing else (no log line, no picture).
     */
    public boolean cmdAllowed(EntityPlayerMP p, String op, long now) {
        int kind = CmdRateMath.kind(op);
        if (kind == CmdRateMath.FREE) {
            return true;
        }
        String who = p.getCommandSenderName().toLowerCase();
        long[] r = cmdRate.get(who);
        if (r == null) {
            if (cmdRate.size() > 256) {
                for (java.util.Iterator<long[]> it = cmdRate.values().iterator(); it.hasNext();) {
                    long[] o = it.next();
                    if (o[0] < now && o[1] < now) {
                        it.remove();
                    }
                }
            }
            r = new long[3];
            r[2] = Long.MIN_VALUE / 2;
            cmdRate.put(who, r);
        }
        int i = kind == CmdRateMath.LIGHT ? 0 : 1;
        long next = CmdRateMath.take(r[i], kind, now);
        if (next >= 0) {
            r[i] = next;
            return true;
        }
        if (now - r[2] >= 20 || now < r[2]) {
            r[2] = now;
            ChatComponentTranslation c = new ChatComponentTranslation("sva.ad.msg.tooFast");
            c.getChatStyle().setColor(EnumChatFormatting.GOLD);
            p.addChatComponentMessage(c);
        }
        return false;
    }

    // ------------------------------------------------------------------ the screens' commands

    public void command(EntityPlayerMP p, NetADSVA.Cmd m) {
        if (p == null || p.worldObj == null) {
            return;
        }
        World w = p.worldObj;
        if (m.op == NetADSVA.XPDR_SET || m.op == NetADSVA.XPDR_IDENT) {
            Entity e = w.getEntityByID(m.entity);
            if (!(e instanceof EntityVehicleSVA) || !xpdrOperator((EntityVehicleSVA) e, p) || !XpdrMath.fitted(((EntityVehicleSVA) e).getVehicleType())) {
                return;                                             // only the driver (the liner: either pilot) works the transponder
            }
            EntityVehicleSVA v = (EntityVehicleSVA) e;
            if (m.op == NetADSVA.XPDR_IDENT) {
                ident(v);
            } else {
                // audit 2026-10-22 (bug 12): the changed fields only ("Ch"; none - an old client: all three); review 2026-10-27: a
                // new code at most once a second (XpdrMath.CODE_RATE_TICKS - a quicker one waits for its second, the latest wins)
                int ch = m.data.hasKey("Ch") ? m.data.getInteger("Ch") & XpdrMath.CH_ALL : XpdrMath.CH_ALL;
                setXpdr(v, ch, m.data.getInteger("M"), m.data.getInteger("C"), m.data.getBoolean("D"));
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
            if (cmdAllowed(p, m.data.getString("Op"), w.getTotalWorldTime())) {
                ((TileEntityCommandPostSVA) te).command(p, m.data);
            }
        } else if (m.op == NetADSVA.LAUNCHER_SET && te instanceof TileEntityLauncherSVA) {
            ((TileEntityLauncherSVA) te).applySettings(p, m.data);  // code-only wave (item 25): the rule - the owner's
        } else if (m.op == NetADSVA.KP_LINK && (te instanceof TileEntityLauncherSVA || te instanceof com.sva.block.TileEntityRadarSVA)
                && cmdAllowed(p, CmdRateMath.LINK, w.getTotalWorldTime())) {
            relink(p, (TileEntityAirDefenseSVA) te);                // code-only wave (item 25): to or from a code-only post - its owner
        } else if (m.op == NetADSVA.AD_EQUIP && com.sva.block.BlockAirDefenseSVA.realPlayer(p)) {
            if (te instanceof TileEntityCommandPostSVA) {
                ((TileEntityCommandPostSVA) te).removeViewer(p);
            }
            p.openGui(com.sva.SVAMod.instance, te instanceof TileEntityLauncherSVA ? com.sva.inventory.GuiHandlerSVA.LAUNCHER
                    : com.sva.inventory.GuiHandlerSVA.AD_EQUIP, w, m.x, m.y, m.z);
        } else if (m.op == NetADSVA.KP_OPEN && te instanceof TileEntityCommandPostSVA && cmdAllowed(p, CmdRateMath.OPEN, w.getTotalWorldTime())) {
            com.sva.block.BlockAirDefenseSVA.openKp((TileEntityCommandPostSVA) te, p);
        }
    }
}
