package com.sva.debug;

import java.util.ArrayList;
import java.util.List;

import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityCommandPostSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemAmmoSVA;
import com.sva.item.ItemCodeCardSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Air-defence wave world test, run by WorldTestSVA alongside its other phases from tick START in its own area (x spawn +
 * 3000, z spawn - 3000, a floor at Y 150) with its own owner "TestAD": a command post, the three radars, an AA gun and a
 * missile system; a forbidden circle «3-1» (r 100) round the post. Light planes hang in the air (held in place every tick):
 * a stranger inside the zone with a silent transponder (A), one with today's code from a printed code card's day (B), one
 * squawking 7700 (C), the owner's own (D), a stranger outside the zone 150 blocks off (E), the same with the radar-absorbent
 * coating (H) and one hidden behind a stone wall (G).
 * - the picture: A violator, B friend, C emergency, D own, E stranger; G not seen (terrain); H seen by the surveillance
 *   radar but not by the height finder (coating x0.6); the alarm (redstone 15), the log (zone entry, 7700);
 * - «только предупреждения»: the gun warns A (lock, the RWR's lock paint) but fires nothing; nobody else engaged;
 * - «fire»: the gun's bursts and a missile hit A (its hull drops), the ammunition is spent, the log has the launch; B / C / D
 *   untouched; IDENT of B lands in the log; «свой на 10 минут» on A ends its engagement.
 */
final class WorldTestAirDefenseSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-0000000000ad", OWNER_NAME = "TestAD";
    private static final String STRANGER = "00000000-5bad-4e57-0000-00000000bad1";
    static final int START = 1300;
    private static final int Y = 150, FLY = Y + 30;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private TileEntityCommandPostSVA kp;
    private TileEntityRadarSVA survey, track, height;
    private TileEntityLauncherSVA gun, sam;
    private EntityVehicleSVA a, b, c, d, e, g, h;
    private final List<double[]> hold = new ArrayList<double[]>();
    private final List<EntityVehicleSVA> held = new ArrayList<EntityVehicleSVA>();
    private float hp0;
    private int shells0, missiles0;
    private String mode0;
    private int minWarn0;
    private boolean pvp0, unmanned0, lockSeen;
    boolean done;

    WorldTestAirDefenseSVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = kp == null ? 9 : 1;
        }
        int dt = ticks - t0;
        if (phase >= 1 && phase < 9) {
            for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kp, survey, track, height, gun, sam}) {
                te.fillForTest();                                   // stands in for a cable feed
            }
            for (int i = 0; i < held.size(); i++) {
                EntityVehicleSVA v = held.get(i);
                double[] p = hold.get(i);
                if (v != null && !v.isDead) {
                    v.setPosition(p[0], p[1], p[2]);
                    v.motionX = v.motionY = v.motionZ = 0;
                }
            }
            if (gun.engagements().containsKey(Integer.valueOf(a.getEntityId())) && gun.engagements().get(Integer.valueOf(a.getEntityId()))[1] >= RoeMath.ST_LOCK) {
                for (double[] th : AirDefenseSVA.INSTANCE.threats(a)) {
                    lockSeen |= (int) th[0] == RadarMath.T_LOCK;
                }
            }
        }
        if (phase == 1 && dt >= 260) {
            picture(w);
            ConfigSVA.adMode = ConfigSVA.AD_FIRE;
            hp0 = a.state.hp;
            shells0 = gun.ammo();
            missiles0 = sam.ammo();
            b.state.xpdr.identUntil = -1;
            AirDefenseSVA.INSTANCE.ident(b);
            phase = 2;
        } else if (phase == 2 && dt >= 560) {
            fire();
            kp.debugGrant(a.getEntityId());
            phase = 3;
        } else if (phase == 3 && dt >= 640) {
            check(!gun.engagements().containsKey(Integer.valueOf(a.getEntityId())) && !sam.engagements().containsKey(Integer.valueOf(a.getEntityId()))
                    && kp.tracks().get(Integer.valueOf(a.getEntityId())) != null
                    && kp.tracks().get(Integer.valueOf(a.getEntityId())).cls == RoeMath.FRIEND,
                    "ad world: \"friendly for 10 min\" - A turns friend, the launchers let it go");
            phase = 9;
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private void picture(World w) {
        java.util.Map<Integer, AirDefenseSVA.Contact> tr = kp.tracks();
        AirDefenseSVA.Contact ta = tr.get(Integer.valueOf(a.getEntityId())), tb = tr.get(Integer.valueOf(b.getEntityId())),
                tc = tr.get(Integer.valueOf(c.getEntityId())), td = tr.get(Integer.valueOf(d.getEntityId())), te = tr.get(Integer.valueOf(e.getEntityId()));
        check(ta != null && tb != null && tc != null && td != null && te != null, "ad world: the command post's picture holds the five planes in sight ("
                + tr.size() + " tracks)");
        if (ta == null || tb == null || tc == null || td == null || te == null) {
            return;
        }
        check(ta.cls == RoeMath.VIOLATOR && tb.cls == RoeMath.FRIEND && tc.cls == RoeMath.EMERGENCY && td.cls == RoeMath.OWN && te.cls == RoeMath.FOREIGN,
                "ad world: classes - silent stranger in the zone violator, day code friend, 7700 emergency, the owner's own, outside the zone stranger ("
                        + ta.cls + tb.cls + tc.cls + td.cls + te.cls + ")");
        check(tb.replied && tb.code == kp.dayCode() && tb.altKnown && !ta.replied && ta.altKnown && (ta.kinds & 1 << RadarMath.SURVEY) != 0
                && (ta.kinds & 1 << RadarMath.HEIGHT) != 0 && b.state.xpdr.lastInterrogated > 0,
                "ad world: the surveillance radar reads B's transponder (code, mode C altitude), the height finder gives silent A's altitude");
        check(Math.abs(ta.x - a.posX) <= 8.5 && Math.abs(ta.z - a.posZ) <= 8.5, "ad world: the track is within the radars' error of the plane");
        check(!tr.containsKey(Integer.valueOf(g.getEntityId())) && !survey.contacts().containsKey(Integer.valueOf(g.getEntityId())),
                "ad world: G behind the stone wall - no beam, no contact");
        check(survey.contacts().containsKey(Integer.valueOf(h.getEntityId())) && !height.contacts().containsKey(Integer.valueOf(h.getEntityId()))
                && height.contacts().containsKey(Integer.valueOf(e.getEntityId())),
                "ad world: 150 blocks off the coated H is seen by the surveillance radar but not by the height finder (E without coating is)");
        check(kp.isAlarm() && ModContentSVA.commandPost.isProvidingWeakPower(w, kp.xCoord, kp.yCoord, kp.zCoord, 2) == 15,
                "ad world: a violator in the zone raised the alarm - redstone 15");
        check(log("sva.ad.log.zoneIn") && log("sva.ad.log.emergency"), "ad world: the log has the zone entry and the 7700");
        check(gun.engagements().containsKey(Integer.valueOf(a.getEntityId())) && gun.engagements().size() == 1 && !sam.engagements().containsKey(Integer.valueOf(b.getEntityId()))
                && !sam.engagements().containsKey(Integer.valueOf(c.getEntityId())) && !sam.engagements().containsKey(Integer.valueOf(d.getEntityId()))
                && !sam.engagements().containsKey(Integer.valueOf(e.getEntityId())), "ad world: only the violator A is engaged (rule: violators)");
        check(lockSeen && a.state.hp == a.state.maxHp() && gun.ammo() == 64 && log("sva.ad.log.lock"),
                "ad world: \"warnings only\" - A locked (the RWR paint, the log) but not one shot fired");
    }

    private void fire() {
        boolean launched = log("sva.ad.log.launch");
        check(a.state.hp < hp0, "ad world: \"fire\" - A's hull hit (" + Math.round(hp0) + " -> " + Math.round(a.state.hp) + ")");
        check(gun.ammo() < shells0 && sam.ammo() < missiles0 && launched && log("sva.ad.log.fire"),
                "ad world: the gun's bursts and a missile launch spent their ammunition (shells " + shells0 + " -> " + gun.ammo() + ", missiles "
                        + missiles0 + " -> " + sam.ammo() + ") and went to the log");
        check(b.state.hp == b.state.maxHp() && c.state.hp == c.state.maxHp() && d.state.hp == d.state.maxHp() && e.state.hp == e.state.maxHp(),
                "ad world: the friend, the emergency, the owner's and the stranger outside the zone are untouched");
        check(log("sva.ad.log.ident"), "ad world: B's IDENT is in the log");
    }

    private boolean log(String key) {
        for (String[] l : kp.logLines()) {
            if (l.length > 1 && l[1].equals(key)) {
                return true;
            }
        }
        return false;
    }

    private EntityVehicleSVA plane(World w, double x, double y, double z, String owner, String ownerName) {
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), new VehicleState(VehicleType.PLANE));
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, s, x, y, z, 0F, owner, ownerName);
        if (v != null) {
            held.add(v);
            hold.add(new double[] {x, y, z});
        }
        return v;
    }

    /**
     * Multiblock wave: placed whole (BlockAirDefenseSVA.build, the front to the north - the structure grows south and east
     * of (x, z): the post x..x+1, the survey radar x-1..x+1 / z..z+2, the 2x2s x..x+1 / z..z+1, the missile system x-1..x+1 /
     * z..z+2 - none overlapping at the spacing below).
     */
    @SuppressWarnings("unchecked")
    private <T extends TileEntity> T block(World w, int x, int z, net.minecraft.block.Block bl, int meta, Class<T> c) {
        com.sva.block.BlockAirDefenseSVA.build(w, x, Y + 1, z, (com.sva.block.BlockAirDefenseSVA) bl, meta, 2);
        TileEntity te = w.getTileEntity(x, Y + 1, z);
        if (te instanceof TileEntityAirDefenseSVA) {
            ((TileEntityAirDefenseSVA) te).setOwnerName(OWNER_NAME);
        }
        return c.isInstance(te) ? (T) te : null;
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 3000;
        z0 = sp.posZ - 3000;
        keepLoaded(w);
        mode0 = ConfigSVA.adMode;
        minWarn0 = ConfigSVA.adMinWarningSeconds;
        pvp0 = ConfigSVA.adRequirePvp;
        unmanned0 = ConfigSVA.adFireOnUnmanned;
        ConfigSVA.adMode = ConfigSVA.AD_WARNINGS;
        ConfigSVA.adMinWarningSeconds = 0;
        w.getWorldInfo().setRaining(false);                         // the ranges below are for clear weather
        w.getWorldInfo().setThundering(false);
        ConfigSVA.adRequirePvp = false;
        ConfigSVA.adFireOnUnmanned = true;
        area(w, true);
        kp = block(w, x0, z0, ModContentSVA.commandPost, 2, TileEntityCommandPostSVA.class);
        survey = block(w, x0 + 3, z0, ModContentSVA.radar, RadarMath.SURVEY, TileEntityRadarSVA.class);
        height = block(w, x0 + 6, z0, ModContentSVA.radar, RadarMath.HEIGHT, TileEntityRadarSVA.class);
        track = block(w, x0 + 9, z0, ModContentSVA.radar, RadarMath.TRACK, TileEntityRadarSVA.class);
        gun = block(w, x0, z0 + 4, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        sam = block(w, x0 + 4, z0 + 4, ModContentSVA.launcher, TileEntityLauncherSVA.MISSILE, TileEntityLauncherSVA.class);
        check(kp != null && survey != null && height != null && track != null && gun != null && sam != null,
                "ad world: the command post, three radars and two launchers placed");
        if (kp == null || survey == null || height == null || track == null || gun == null || sam == null) {
            kp = null;
            return;
        }
        kp.debugSetup("sekret", new int[] {01234}, 0, null);
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.name = "3-1";
        z.type = ZoneMath.FORBIDDEN;
        z.shape = ZoneMath.CIRCLE;
        z.cx = x0;
        z.cz = z0;
        z.r = 100;
        List<ZoneMath.Zone> zs = new ArrayList<ZoneMath.Zone>();
        zs.add(z);
        kp.setZones(zs);
        gun.debugSettings(RoeMath.VIOLATORS, true, true, 2);
        sam.debugSettings(RoeMath.VIOLATORS, true, true, 2);
        gun.debugAmmo(ModContentSVA.ammo.stackOf(ItemAmmoSVA.SHELLS, 64));
        for (int i = 0; i < 4; i++) {
            sam.debugAmmo(ModContentSVA.ammo.stackOf(ItemAmmoSVA.MISSILE, 1));
        }
        // the stone wall that hides G from the radars
        for (int x = x0 - 6; x <= x0 + 14; x++) {
            for (int y = Y + 1; y <= Y + 14; y++) {
                w.setBlock(x, y, z0 - 10, Blocks.stone, 0, 2);
            }
        }
        a = plane(w, x0 + 40.5, FLY, z0 + 0.5, STRANGER, "Intruder");
        b = plane(w, x0 - 40.5, FLY, z0 + 0.5, STRANGER, "Guest");
        c = plane(w, x0 + 0.5, FLY, z0 + 50.5, STRANGER, "Mayday");
        d = plane(w, x0 + 20.5, FLY, z0 + 40.5, OWNER, OWNER_NAME);
        e = plane(w, x0 + 150.5, FLY, z0 + 0.5, STRANGER, "Passer");
        h = plane(w, x0 + 150.5, FLY, z0 + 30.5, STRANGER, "Stealth");
        g = plane(w, x0 + 3.5, Y + 6, z0 - 30.5, STRANGER, "Hider");
        check(a != null && b != null && c != null && d != null && e != null && g != null && h != null, "ad world: the test planes placed");
        if (a == null || b == null || c == null || d == null || e == null || g == null || h == null) {
            kp = null;
            return;
        }
        a.state.xpdr.mode = XpdrMath.OFF;
        // B: today's code from a code card (as AirDefenseSVA.cardCode would set it for its pilot)
        ItemStack card = new ItemStack(ModContentSVA.codeCard);
        kp.printCardForTest(card);
        b.state.xpdr.mode = XpdrMath.C;
        b.state.xpdr.setCode(ItemCodeCardSVA.code(card), w.getTotalWorldTime());
        c.state.xpdr.mode = XpdrMath.A;
        c.state.xpdr.setCode(XpdrMath.EMERGENCY, w.getTotalWorldTime());
        h.state.tuning = new int[] {TuningMath.RAM_COAT, -1, -1, -1};
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 8; x <= x0 + 16; x++) {
            for (int z = z0 - 12; z <= z0 + 8; z++) {
                for (int y = Y + 1; y <= Y + 16; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 72) >> 4; cx <= (x0 + 190) >> 4; cx++) {
            for (int cz = (z0 - 72) >> 4; cz <= (z0 + 90) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void finish(World w) {
        ConfigSVA.adMode = mode0 == null ? ConfigSVA.AD_WARNINGS : mode0;
        ConfigSVA.adMinWarningSeconds = minWarn0;
        ConfigSVA.adRequirePvp = pvp0;
        ConfigSVA.adFireOnUnmanned = unmanned0;
        for (EntityVehicleSVA v : held) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        held.clear();
        hold.clear();
        for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kp, survey, track, height, gun, sam}) {
            if (te != null) {
                te.noDrop = true;                                   // cleared below: no items left lying about
            }
        }
        if (x0 != 0 || z0 != 0) {
            for (int x = x0 - 6; x <= x0 + 14; x++) {
                for (int y = Y + 1; y <= Y + 14; y++) {
                    w.setBlockToAir(x, y, z0 - 10);
                }
            }
            area(w, false);
        }
        AirDefenseSVA.INSTANCE.shots().clear();
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
            reg.removeOwner(STRANGER);
        }
        done = true;
    }

    void abort(World w) {
        try {
            finish(w);
        } catch (Throwable t2) {
            done = true;
        }
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }
}
