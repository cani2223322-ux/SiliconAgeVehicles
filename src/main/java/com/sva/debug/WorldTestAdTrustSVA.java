package com.sva.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.NoRadioMath;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityCommandPostSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemAmmoSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Audit 2026-10-22 world test (the friend / foe trust model), run by WorldTestSVA alongside its other phases from tick START in
 * its own area (x spawn + 7400, z spawn - 7400, a floor at Y 150), owner «TestADT», «airdefense.mode» warnings:
 * <ul>
 * <li>bug 2: an AA gun linked to an UNPOWERED command post (A) with a forbidden zone round it - its own eyes interrogate:
 * 7700, today's code (the owner's secret), an own code, a Mode S white-list board of its owner, «свой на 10 минут» are never
 * engaged; «только вести» on A holds its fire; a silent stranger and a plane squawking 7700 with its board dark (no answer
 * without power) are violators; a 7600 near the end of its 2 minutes switched to 7000 and back - the grace not restarted,
 * the violator after it;</li>
 * <li>bug 18: the owner's posts give one day code;</li>
 * <li>bug 19: a launcher with no link answers to the nearest POWERED post (F) though an unpowered one (N) is nearer; only F
 * lists it; linked to N by hand - no picture (own eyes); the screen's «next post» moves it back to F;</li>
 * <li>bug 15, item 10, 7500 (post F with a tracking radar only): a new track is UNKNOWN - not engaged by the gun «по всем
 * чужим» - until a surveillance cycle passes, then the post interrogates it itself (a stranger, engaged); «только вести» -
 * the gun lets it go and does not take it again; 7500 - in the log and the alarm.</li>
 * </ul>
 */
final class WorldTestAdTrustSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000adb0", OWNER_NAME = "TestADT";
    private static final String STRANGER = "00000000-5bad-4e57-0000-00000000adb1";
    private static final String SECRET = "trustT";
    static final int START = 2300;
    private static final int Y = 150, FLY = Y + 20, MAX_TICKS = 1500;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    boolean done;
    private TileEntityCommandPostSVA kpA, kpN, kpF;
    private TileEntityLauncherSVA gunA, gunF;
    private TileEntityRadarSVA trackF;
    private EntityVehicleSVA em, day, own, white, grant, held, nr, silent, dark, t2, hj;
    private final List<double[]> hold = new ArrayList<double[]>();
    private final List<EntityVehicleSVA> heldList = new ArrayList<EntityVehicleSVA>();
    private String mode0;
    private int minWarn0;
    private boolean pvp0, unmanned0;
    private boolean linksDone, picDone, nrOff, nrBack, nrDone, unknownSeen, askedDone, holdSent, holdDone, hijackDone, hijackAgain;
    /** Audit review: the 7500 toggled - 0 not yet, 1 on 7000, 2 back on 7500. */
    private int hjStep;
    private int askedAt = -1, holdAt = -1;
    private NoRadioMath.Entry nrEntry;

    WorldTestAdTrustSVA(WorldTestSVA t) {
        this.t = t;
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = kpA == null ? 9 : 1;
        }
        int k = ticks - t0;
        if (phase == 1) {
            for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kpF, gunA, gunF, trackF}) {
                te.fillForTest();                                   // A and N stay without power
            }
            for (int i = 0; i < heldList.size(); i++) {
                EntityVehicleSVA v = heldList.get(i);
                double[] p = hold.get(i);
                if (v != null && !v.isDead) {
                    v.setPosition(p[0], p[1], p[2]);
                    v.motionX = v.motionY = v.motionZ = 0;
                }
            }
            run(w, k);
            if (k > MAX_TICKS) {
                check(linksDone && picDone && nrDone && askedDone && holdDone && hijackDone && hijackAgain, "ad trust world: every stage finished in time (links "
                        + linksDone + ", picture " + picDone + ", 7600 " + nrDone + ", unknown " + askedDone + ", hold " + holdDone + ", 7500 " + hijackDone
                        + ", 7500 again " + hijackAgain + ")");
                phase = 9;
            }
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private void run(World w, int k) {
        long now = w.getTotalWorldTime();
        if (k == 20) {
            links(w);
        }
        if (k == 150) {
            day.state.xpdr.setCode(AirDefenseDataSVA.keyCodeNow(w, OWNER_NAME), now);    // a new key period since the setup
        }
        if (k == 200) {
            picture(w);
        }
        noRadio(w, k, now);
        trackF(w, k);
        if (linksDone && picDone && nrDone && askedDone && holdDone && hijackDone && hijackAgain) {
            phase = 9;
        }
    }

    // ---------------------------------------------------------------- bugs 18, 19: the posts

    private void links(World w) {
        TileEntityCommandPostSVA l = AirDefenseSVA.INSTANCE.linkedPost(gunF);
        kpF.relinkNow();
        kpN.relinkNow();
        check(!kpN.isPowered() && kpF.isPowered() && l == kpF && gunF.kpLink().equals(kpF.key()) && kpF.launchers().contains(gunF)
                && !kpN.launchers().contains(gunF) && kpF.radars().contains(trackF),
                "ad trust world (bug 19): no link - the nearest POWERED post (the unpowered one nearer is passed over), only it lists the launcher");
        gunF.setKpLink(kpN.key());
        TileEntityCommandPostSVA ln = AirDefenseSVA.INSTANCE.linkedPost(gunF), wn = AirDefenseSVA.INSTANCE.workingPost(gunF);
        TileEntityCommandPostSVA back = AirDefenseSVA.INSTANCE.cycleLink(gunF);
        check(ln == kpN && wn == null && back == kpF && gunF.kpLink().equals(kpF.key()),
                "ad trust world (bug 19): linked to the unpowered post by hand - no picture (own eyes); the screen's next post - the powered one again");
        AirDefenseDataSVA kd = AirDefenseDataSVA.get(w);
        int day = kd == null ? -2 : XpdrMath.dayCode(SECRET, kd.pepper(), kd.gen(OWNER_NAME), AirDefenseDataSVA.keyPeriod(w));
        check(kpA.dayCode() == day && kpF.dayCode() == day && kpN.dayCode() == day,
                "ad trust world (bug 18): the owner's three posts give one day code (" + XpdrMath.format(day) + ")");
        linksDone = true;
    }

    // ---------------------------------------------------------------- bug 2: a launcher whose post is off

    private int[] cls(EntityVehicleSVA v) {
        int[] c = gunA.lastPicture().get(Integer.valueOf(v.getEntityId()));
        return c == null ? new int[] {-1, 0, 0} : c;
    }

    private void picture(World w) {
        boolean noPost = AirDefenseSVA.INSTANCE.workingPost(gunA) == null && AirDefenseSVA.INSTANCE.linkedPost(gunA) == kpA;
        check(noPost && cls(em)[0] == RoeMath.EMERGENCY && cls(day)[0] == RoeMath.FRIEND && cls(own)[0] == RoeMath.FRIEND && cls(white)[0] == RoeMath.FRIEND
                && cls(grant)[0] == RoeMath.FRIEND && cls(nr)[0] == RoeMath.NO_RADIO,
                "ad trust world (bug 2): the gun of an unpowered post interrogates itself - 7700 emergency, the day code / an own code / the white list / "
                        + "«свой на 10 минут» friends, 7600 in its grace (" + cls(em)[0] + cls(day)[0] + cls(own)[0] + cls(white)[0] + cls(grant)[0] + cls(nr)[0] + ")");
        check(cls(held)[1] == 1 && cls(silent)[0] == RoeMath.VIOLATOR && cls(dark)[0] == RoeMath.VIOLATOR && cls(dark)[2] == 0 && !AirDefenseSVA.xpdrOn(dark),
                "ad trust world: «только вести» of the post holds the gun's fire; a silent stranger and a 7700 with its board dark (no answer without power) violators");
        boolean wrecked = true;
        if (ConfigSVA.wear) {
            float hp = em.state.hp;
            em.state.hp = 0;
            wrecked = !AirDefenseSVA.xpdrOn(em);
            em.state.hp = hp;
        }
        check(wrecked && AirDefenseSVA.xpdrOn(em), "ad trust world (4.2): a wreck's transponder answers nothing, a powered one does");
        boolean bad = false;
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {em, day, own, white, grant, held, nr}) {
            bad |= gunA.engagements().containsKey(Integer.valueOf(v.getEntityId()));
        }
        check(!bad && (gunA.engagements().containsKey(Integer.valueOf(silent.getEntityId())) || gunA.engagements().containsKey(Integer.valueOf(dark.getEntityId()))),
                "ad trust world (bug 2): none of the friends, the 7700, the 7600 or the held one is engaged - a violator is (" + gunA.engagements().keySet() + ")");
        picDone = true;
    }

    /** 7600 near the end of its 2 minutes: 7000 for a moment and back - the memory goes on, the violator after it. */
    private void noRadio(World w, int k, long now) {
        if (!picDone || nrDone) {
            return;
        }
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        String vid = AirDefenseSVA.vehKey(nr);
        if (!nrOff) {
            nrEntry = d.freeNoRadioEntry(vid);
            nr.state.xpdr.setCode(XpdrMath.VFR, now);
            nrOff = true;
        } else if (!nrBack && k >= 215) {
            NoRadioMath.Entry e = d.freeNoRadioEntry(vid);
            check(e != null && e == nrEntry && e.since < 0 && e.used >= XpdrMath.NO_RADIO_GRACE - 200,
                    "ad trust world (bug 8): off 7600 - the launchers' memory paused, not dropped (" + (e == null ? "none" : e.used + " ticks") + ")");
            nr.state.xpdr.setCode(XpdrMath.NO_RADIO, now);
            nrBack = true;
        } else if (nrBack && k >= 420) {
            NoRadioMath.Entry e = d.freeNoRadioEntry(vid);
            check(e == nrEntry && e.since >= 0 && cls(nr)[0] == RoeMath.VIOLATOR,
                    "ad trust world (bug 2 / 8): back on 7600 - the 2 minutes go on and run out: a violator (class " + cls(nr)[0] + "), not a fresh grace");
            nrDone = true;
        }
    }

    // ---------------------------------------------------------------- bug 15, item 10, 7500: post F

    private void trackF(World w, int k) {
        AirDefenseSVA.Contact c = kpF.tracks().get(Integer.valueOf(t2.getEntityId()));
        if (!unknownSeen && c != null && c.askedAt < 0) {
            check(c.cls == RoeMath.UNKNOWN && !gunF.engagements().containsKey(Integer.valueOf(t2.getEntityId())),
                    "ad trust world (bug 15): a track no surveillance radar interrogated - UNKNOWN, the gun «по всем чужим» does not take it");
            unknownSeen = true;
        }
        if (unknownSeen && !askedDone && c != null && c.askedAt >= 0) {
            if (askedAt < 0) {
                askedAt = k;
            }
            if (k - askedAt >= 20) {
                check(c.cls == RoeMath.FOREIGN && c.replied && c.code == XpdrMath.VFR && gunF.engagements().containsKey(Integer.valueOf(t2.getEntityId())),
                        "ad trust world (bug 15): a surveillance cycle later the post interrogates it itself - a stranger squawking 7000, the gun takes it");
                askedDone = true;
            }
        }
        if (!unknownSeen && k > 600) {
            check(false, "ad trust world (bug 15): post F never had the track");
            unknownSeen = askedDone = holdSent = holdDone = true;
        }
        if (askedDone && !holdSent && c != null) {
            NBTTagCompound d = new NBTTagCompound();
            d.setString("Op", "track");
            d.setInteger("Id", t2.getEntityId());
            kpF.command(null, d);                                   // «только вести» from the screen's menu
            holdSent = true;
            holdAt = k;
        }
        if (holdSent && !holdDone && k - holdAt >= 40) {
            AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
            AirDefenseDataSVA.KpInfo i = dd == null ? null : dd.info(kpF.key(), false);
            check(c != null && c.held && i != null && i.held(AirDefenseSVA.vehKey(t2), w.getTotalWorldTime())
                    && !gunF.engagements().containsKey(Integer.valueOf(t2.getEntityId())) && RoeMath.roeWants(RoeMath.FOREIGN_ALL, c.cls, false, false),
                    "ad trust world (item 10): «только вести» - the gun «по всем чужим» lets the stranger go and does not take it again (kept by its persistent id)");
            holdDone = true;
        }
        if (!hijackDone) {
            boolean logged = false;
            for (String[] l : kpF.logLines()) {
                logged |= l.length > 1 && l[1].equals("sva.ad.log.hijack");
            }
            if (logged) {
                check(kpF.isAlarm(), "ad trust world (7500): a 7500 - in the post's log and its alarm on");
                hijackDone = true;
            } else if (k > 900) {
                check(false, "ad trust world (7500): the post never logged the 7500");
                hijackDone = hijackAgain = true;
            }
        } else if (!hijackAgain) {
            // audit review 2026-10-22: 7000 and back to 7500 - the post sees both, the alarm is not told again (it was every round)
            AirDefenseSVA.Contact h = kpF.tracks().get(Integer.valueOf(hj.getEntityId()));
            long now = w.getTotalWorldTime();
            if (hjStep == 0) {
                hj.state.xpdr.setCode(XpdrMath.VFR, now);
                hjStep = 1;
            } else if (hjStep == 1 && h != null && h.replied && h.code == XpdrMath.VFR) {
                hj.state.xpdr.setCode(XpdrMath.HIJACK, now);
                hjStep = 2;
            } else if (hjStep == 2 && h != null && h.replied && h.code == XpdrMath.HIJACK && h.said7500) {
                int n = 0;
                for (String[] l : kpF.logLines()) {
                    n += l.length > 1 && l[1].equals("sva.ad.log.hijack") ? 1 : 0;
                }
                check(n == 1 && kpF.isAlarm(), "ad trust world (review, 7500): 7000 and back to 7500 - the alarm holds, the log / chat not told again within "
                        + XpdrMath.HIJACK_REPEAT / 1200 + " min (" + n + " lines)");
                hijackAgain = true;
            } else if (k > MAX_TICKS - 20) {
                check(false, "ad trust world (review, 7500): the post never saw the code toggled (step " + hjStep + ")");
                hijackAgain = true;
            }
        }
    }

    // ---------------------------------------------------------------- build

    private EntityVehicleSVA plane(World w, double x, double y, double z, String owner, String name, int mode, int code) {
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.energy = st.maxEnergy();
        ItemStack is = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st);
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, is, x, y, z, 0F, owner, name);
        if (v != null) {
            heldList.add(v);
            hold.add(new double[] {x, y, z});
            v.state.xpdr.dayKey = false;
            v.state.xpdr.mode = mode;
            v.state.xpdr.setCode(code, w.getTotalWorldTime());
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private <T extends TileEntity> T block(World w, int x, int z, net.minecraft.block.Block bl, int meta, Class<T> c) {
        BlockAirDefenseSVA.build(w, x, Y + 1, z, (BlockAirDefenseSVA) bl, meta, 2);
        TileEntity te = w.getTileEntity(x, Y + 1, z);
        if (te instanceof TileEntityAirDefenseSVA) {
            ((TileEntityAirDefenseSVA) te).setOwnerName(OWNER_NAME);
        }
        return c.isInstance(te) ? (T) te : null;
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 7400;
        z0 = sp.posZ - 7400;
        keepLoaded(w);
        mode0 = ConfigSVA.adMode;
        minWarn0 = ConfigSVA.adMinWarningSeconds;
        pvp0 = ConfigSVA.adRequirePvp;
        unmanned0 = ConfigSVA.adFireOnUnmanned;
        ConfigSVA.adMode = ConfigSVA.AD_WARNINGS;
        ConfigSVA.adMinWarningSeconds = 0;
        ConfigSVA.adRequirePvp = false;
        ConfigSVA.adFireOnUnmanned = true;
        w.getWorldInfo().setRaining(false);
        w.getWorldInfo().setThundering(false);
        area(w, true);
        kpA = block(w, x0, z0, ModContentSVA.commandPost, 2, TileEntityCommandPostSVA.class);
        gunA = block(w, x0 + 10, z0 + 10, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        kpN = block(w, x0 + 120, z0, ModContentSVA.commandPost, 2, TileEntityCommandPostSVA.class);
        kpF = block(w, x0 + 165, z0, ModContentSVA.commandPost, 2, TileEntityCommandPostSVA.class);
        gunF = block(w, x0 + 130, z0 + 10, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        trackF = block(w, x0 + 150, z0 + 40, ModContentSVA.radar, RadarMath.TRACK, TileEntityRadarSVA.class);
        check(kpA != null && gunA != null && kpN != null && kpF != null && gunF != null && trackF != null,
                "ad trust world: three command posts, two guns and a tracking radar placed");
        if (kpA == null || gunA == null || kpN == null || kpF == null || gunF == null || trackF == null) {
            kpA = null;
            return;
        }
        kpA.debugSetup(SECRET, new int[] {01234}, 0, null);        // the owner's secret, an own code
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.name = "T-1";
        z.type = ZoneMath.FORBIDDEN;
        z.shape = ZoneMath.CIRCLE;
        z.cx = x0 + 10;
        z.cz = z0 + 10;
        z.r = 60;
        List<ZoneMath.Zone> zs = new ArrayList<ZoneMath.Zone>();
        zs.add(z);
        kpA.setZones(zs);
        gunA.setKpLink(kpA.key());                                  // its post - unpowered
        gunA.debugSettings(RoeMath.VIOLATORS, true, false, 2);
        gunA.debugAmmo(ModContentSVA.ammo.stackOf(ItemAmmoSVA.SHELLS, 64));
        gunF.debugSettings(RoeMath.FOREIGN_ALL, true, false, 2);
        gunF.debugAmmo(ModContentSVA.ammo.stackOf(ItemAmmoSVA.SHELLS, 64));
        long now = w.getTotalWorldTime();
        int dc = AirDefenseDataSVA.keyCodeNow(w, OWNER_NAME);
        // round the gun of post A (its eyes: 48 blocks), inside its forbidden zone
        double gx = x0 + 10.5, gz = z0 + 10.5, r = 26;
        String[] names = {"Mayday", "Daycard", "Owncode", "Whitey", "Granted", "Held", "NoRadio", "Silent", "Dark"};
        EntityVehicleSVA[] v = new EntityVehicleSVA[names.length];
        int[][] xp = {{XpdrMath.A, XpdrMath.EMERGENCY}, {XpdrMath.A, dc}, {XpdrMath.A, 01234}, {XpdrMath.S, XpdrMath.VFR}, {XpdrMath.A, XpdrMath.VFR},
            {XpdrMath.A, XpdrMath.VFR}, {XpdrMath.A, XpdrMath.NO_RADIO}, {XpdrMath.OFF, XpdrMath.VFR}, {XpdrMath.A, XpdrMath.EMERGENCY}};
        for (int i = 0; i < names.length; i++) {
            double a = Math.PI * 2 * i / names.length;
            v[i] = plane(w, gx + Math.cos(a) * r, FLY, gz + Math.sin(a) * r, STRANGER, names[i], xp[i][0], xp[i][1]);
            if (v[i] == null) {
                check(false, "ad trust world: the test aircraft placed");
                kpA = null;
                return;
            }
        }
        em = v[0];
        day = v[1];
        own = v[2];
        white = v[3];
        grant = v[4];
        held = v[5];
        nr = v[6];
        silent = v[7];
        dark = v[8];
        dark.state.switches = SystemsMath.ALL_ON & ~(1 << SystemsMath.SW_BAT);     // the board dark: no answer, its 7700 unheard
        kpA.debugSetup(SECRET, new int[] {01234}, white.getBoardNumber(), "Whitey");
        kpA.debugGrant(grant.getEntityId());
        kpA.debugHold(held.getEntityId());
        // the launchers' 7600 memory: 7600 for all but 300 ticks of its 2 minutes already
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        NoRadioMath.Entry e = d.freeNoRadio(AirDefenseSVA.vehKey(nr), true, now);
        e.used = XpdrMath.NO_RADIO_GRACE - 300;
        // by post F's tracking radar: a stranger squawking 7000 (in the gun's reach) and a 7500
        t2 = plane(w, x0 + 150.5, FLY, z0 + 60.5, STRANGER, "Stranger", XpdrMath.A, XpdrMath.VFR);
        hj = plane(w, x0 + 175.5, FLY, z0 + 60.5, STRANGER, "Hijack", XpdrMath.A, XpdrMath.HIJACK);      // farther from the gun than the stranger
        if (t2 == null || hj == null) {
            check(false, "ad trust world: the test aircraft placed");
            kpA = null;
        }
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 50; x <= x0 + 190; x++) {
            for (int z = z0 - 30; z <= z0 + 80; z++) {
                for (int y = Y + 1; y <= Y + 8; y++) {
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
        for (int cx = (x0 - 60) >> 4; cx <= (x0 + 200) >> 4; cx++) {
            for (int cz = (z0 - 40) >> 4; cz <= (z0 + 90) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void finish(World w) {
        ConfigSVA.adMode = mode0 == null ? ConfigSVA.AD_WARNINGS : mode0;
        ConfigSVA.adMinWarningSeconds = minWarn0;
        ConfigSVA.adRequirePvp = pvp0;
        ConfigSVA.adFireOnUnmanned = unmanned0;
        for (EntityVehicleSVA v : heldList) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        heldList.clear();
        hold.clear();
        for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kpA, kpN, kpF, gunA, gunF, trackF}) {
            if (te != null) {
                te.noDrop = true;
            }
        }
        if (x0 != 0 || z0 != 0) {
            area(w, false);
        }
        AirDefenseSVA.INSTANCE.shots().clear();
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
        if (dd != null) {
            for (TileEntityCommandPostSVA k : new TileEntityCommandPostSVA[] {kpA, kpN, kpF}) {
                if (k != null) {
                    dd.removeKp(k.key());
                }
            }
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
            reg.removeOwner(STRANGER);
        }
        phase = 10;
        done = true;
    }

    void abort(World w) {
        try {
            finish(w);
        } catch (Throwable t2) {
            done = true;
        }
    }

    /** The test's planes' classes as the gun of post A saw them last (debugging a failure). */
    Map<Integer, int[]> lastPicture() {
        return gunA == null ? null : gunA.lastPicture();
    }
}
