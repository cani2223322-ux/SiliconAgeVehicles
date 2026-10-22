package com.sva.debug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.sc.init.ModItems;
import com.sc.machine.UpgradeType;
import com.sva.ConfigSVA;
import com.sva.airdefense.AdTerrainSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.BallisticsMath;
import com.sva.airdefense.CoverageSVA;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityCommandPostSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemAmmoSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Audit 2026-10-22 world test (combat effectiveness and terrain), run by WorldTestSVA alongside its other phases from tick
 * START in its own areas (x spawn + 8800, z spawn - 8800 and 400 blocks east of it, floors at Y 150), owner «TestADC»,
 * «airdefense.mode» fire:
 * <ul>
 * <li>A (bug 3): a command post with a surveillance and a tracking radar, two guns «по всем чужим»; a stranger's plane MOVES
 * across their line of fire 40 blocks off, back and forth (never put back) - every burst aimed at the plane's real position
 * (the mark led by its age, the gun's own eyes inside 48 blocks), the guns hit it; each gun's lock reaches the pilot
 * (warnings hushed per launcher); the shells' hits in the launch log;</li>
 * <li>B: a gun with three overclockers fires every 3 ticks (not every 5, the decision round); an order by an operator - the
 * launch log says who assigned the target;</li>
 * <li>C (bug 4): a gun behind a wall with the post's track of a plane its radars see past the wall - no burst; a shell whose
 * 4-block steps never end inside the wall is stopped by it, one over the wall hits;</li>
 * <li>D (bug 10): a wall in a chunk that is then unloaded still hides a plane from a radar and a launcher's eyes, stops a
 * shell and is known to the coverage map; a fence / slab / stairs row stops a beam, a glass pane does not; a block placed
 * near a site is remembered; a coverage map of another reach is not handed out as fresh.</li>
 * </ul>
 * At the setup: Mode C altitude from the plane's centre; the RWR's list with 16 expired / 16 live paints takes a new one.
 */
final class WorldTestAdCombatSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000adc0", OWNER_NAME = "TestADC";
    private static final String STRANGER = "00000000-5bad-4e57-0000-00000000adc1";
    static final int START = 2500;
    private static final int Y = 150, FLY = Y + 20, MAX_TICKS = 2600;
    /** The moving plane: speed (b/t), half its run along x, its line's offset from the guns (z). */
    private static final double SPEED = 1.12, HALF = 85, OFF = 40;
    private static final int A_TICKS = 450, D_X = 400;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0, dx, dz;
    boolean done;
    private TileEntityCommandPostSVA kp;
    private TileEntityRadarSVA survey, track, r4;
    private TileEntityLauncherSVA g, g2, g3;
    private EntityVehicleSVA m, t2, t3, t5;
    private final List<double[]> hold = new ArrayList<double[]>();
    private final List<EntityVehicleSVA> held = new ArrayList<EntityVehicleSVA>();
    private String mode0;
    private int minWarn0;
    private boolean pvp0, unmanned0;
    private float dmg0;
    private int p0, ammoA, ammoA2, rateAt = -1, rateAmmo, logAt = -1, fireAt3 = -1, ammo3, dStep, unloadAt = -1;
    /** Audit review: the walled gun had the target cleared for fire (ROE, mode) at a round of phase C - only the wall stopped it. */
    private boolean ready3;
    private boolean saidChecked, assignSent, unloadQueued;
    private int[] c1, c2;

    WorldTestAdCombatSVA(WorldTestSVA t) {
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
            phase = kp == null ? 9 : 1;
            p0 = 0;
        }
        int k = ticks - t0;
        if (phase >= 1 && phase < 9) {
            for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kp, survey, track, g, g2, g3, r4}) {
                te.fillForTest();                                   // stands in for a cable feed
            }
            if (m != null && !m.isDead) {
                double[] p = path(k);
                m.setPosition(p[0], FLY, p[1]);
                m.motionX = m.motionY = m.motionZ = 0;
            }
            for (int i = 0; i < held.size(); i++) {
                EntityVehicleSVA v = held.get(i);
                double[] p = hold.get(i);
                if (v != null && !v.isDead) {
                    v.setPosition(p[0], p[1], p[2]);
                    v.motionX = v.motionY = v.motionZ = 0;
                }
            }
            if (phase == 1) {
                phaseA(w, k);
            } else if (phase == 2) {
                phaseB(w, k);
            } else if (phase == 3) {
                phaseC(w, k);
            } else if (phase == 4) {
                phaseD(w, k);
            }
            if (k > MAX_TICKS && phase < 9) {
                check(false, "ad combat world: every stage finished in time (stopped in stage " + phase + ")");
                phase = 9;
            }
        }
        if (phase == 9) {
            finish(w);
        }
    }

    /** The guns' muzzle (x, z) - the moving plane's line runs OFF blocks off it. */
    private double gx() {
        return x0 + 0.5;
    }

    private double gz() {
        return z0 + 4.5;
    }

    /** The moving plane at tick k: back and forth along x, HALF each way, SPEED a tick (x, z). */
    private double[] path(int k) {
        double s = SPEED * k % (4 * HALF);
        double along = s < 2 * HALF ? -HALF + s : HALF - (s - 2 * HALF);
        return new double[] {gx() + along, gz() + OFF};
    }

    // ---------------------------------------------------------------- A: a moving plane

    private void phaseA(World w, int k) {
        Integer id = Integer.valueOf(m.getEntityId());
        if (!saidChecked && g.engagements().containsKey(id) && g2.engagements().containsKey(id) && g.engagements().get(id)[1] >= RoeMath.ST_LOCK
                && g2.engagements().get(id)[1] >= RoeMath.ST_LOCK) {
            check(AirDefenseSVA.INSTANCE.debugSaid(m, "lock|" + g.key()) && AirDefenseSVA.INSTANCE.debugSaid(m, "lock|" + g2.key()),
                    "ad combat world: two guns lock the same plane - each one's lock and countdown reach the pilot (hushed per launcher, not per plane)");
            saidChecked = true;
        }
        if (k < A_TICKS) {
            return;
        }
        int track0 = 0, own = 0, old = 0;
        double worstTrack = 0, worstOwn = 0;
        for (TileEntityLauncherSVA l : new TileEntityLauncherSVA[] {g, g2}) {
            double ax = l.xCoord + 0.5, ay = l.yCoord + 1.5, az = l.zCoord + 0.5;
            for (double[] r : l.aimLog()) {
                if (Math.abs(r[4] - gx()) > HALF - 30) {
                    continue;                                       // near the turn: the velocity's 5-tick sample lags
                }
                // where the plane really meets the shells (they step at the end of the burst's tick, the plane moves the next one)
                double[] want = BallisticsMath.gunAim(ax, ay, az, r[4], r[5], r[6], r[7], r[8], r[9], BallisticsMath.GUN_SPEED, BallisticsMath.SHELL_LIFE);
                double err = want == null ? 99 : BallisticsMath.dist(r[0], r[1], r[2], want[0], want[1], want[2]);
                if (r[11] > 0) {
                    own++;
                    worstOwn = Math.max(worstOwn, err);
                } else {
                    track0++;
                    worstTrack = Math.max(worstTrack, err);
                    old += r[10] >= 3 ? 1 : 0;
                }
            }
        }
        check(track0 >= 3 && old >= 1 && worstTrack < 2.0,
                "ad combat world (bug 3): bursts on the post's track (" + track0 + ", " + old + " of them on a mark 3+ ticks old) aimed where the moving plane really is "
                        + "(worst " + Math.round(worstTrack * 100) / 100.0 + " blocks; at the mark itself a 1.12 b/t plane was missed by its speed x the age)");
        check(own >= 1 && worstOwn < 0.01, "ad combat world (bug 3): inside 48 blocks the gun aims at the plane's live position (its own eyes, " + own + " bursts)");
        int shells = (ammoA - g.ammo() + ammoA2 - g2.ammo()) * BallisticsMath.GUN_BURST, hits = g.hitsTotal() + g2.hitsTotal();
        check(shells > 0 && hits >= 5 && hits * 10 >= shells * 3,
                "ad combat world (bug 3): the guns hit the moving plane - " + hits + " of " + shells + " shells (the hit radius 2.1 blocks)");
        // B next: the gun with three overclockers on a held plane, by an operator's order
        m.setDead();
        g.debugSettings(RoeMath.HOLD, false, false, 2);
        g2.debugSettings(RoeMath.KP_MARKS, false, false, 2);
        g2.debugUpgrade(0, upgrade(UpgradeType.OVERCLOCKER, 3));
        ConfigSVA.adDamage = 0F;                                     // fired at for a while: no wreck
        t2 = plane(w, x0 + 4.5, Y + 12, z0 + 30.5, "Rate");
        scans();
        phase = 2;
        p0 = k;
    }

    private void scans() {
        survey.scan(RadarMath.SURVEY);
        track.scan(RadarMath.TRACK);
    }

    // ---------------------------------------------------------------- B: the overclocked gun, the operator's order

    private void phaseB(World w, int k) {
        Integer id = Integer.valueOf(t2.getEntityId());
        if (!assignSent && kp.tracks().containsKey(id) && kp.tracks().get(id).askedAt >= 0) {
            NBTTagCompound d = new NBTTagCompound();
            d.setString("Op", "assign");
            d.setInteger("Id", t2.getEntityId());
            d.setString("L", g2.key());
            kp.command(operator(w), d);
            assignSent = true;
        }
        long[] st = g2.engagements().get(id);
        if (rateAt < 0 && st != null && st[1] == RoeMath.ST_FIRE) {
            rateAt = k;
            rateAmmo = g2.ammo();
        }
        if (rateAt >= 0 && logAt < 0 && k - rateAt >= 60) {
            int bursts = rateAmmo - g2.ammo();
            check(g2.fireInterval() == 3 && bursts >= 17,
                    "ad combat world: three overclockers - a burst every " + g2.fireInterval() + " ticks really: " + bursts + " in 60 ticks (the 5-tick round gave 12)");
            AirDefenseSVA.INSTANCE.flushLaunchLog(true);
            logAt = k;
        }
        if (logAt >= 0 && k - logAt >= 40) {
            AirDefenseSVA.INSTANCE.flushLaunchLog(true);
            launchLog();
            nextC(w, k);
        } else if (k - p0 > 500) {
            check(false, "ad combat world: the overclocked gun never got to fire (" + (st == null ? "not engaged" : "stage " + st[1]) + ")");
            nextC(w, k);
        }
    }

    /** The launch log file: who assigned the target, the burst's line saying so, the gun's hits summed up. */
    private void launchLog() {
        if (!ConfigSVA.adLaunchLog) {
            check(true, "ad combat world: the launch log is off in the config - its lines not checked");
            return;
        }
        boolean order = false, burst = false, hitsLine = false;
        File f = AirDefenseSVA.launchLogFile();
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String l;
            while ((l = in.readLine()) != null) {
                order |= l.contains(" assigned to L-") && l.contains(" by Operator");
                burst |= l.contains(OWNER_NAME) && l.contains("fire at") && l.contains("- assigned by Operator");
                hitsLine |= l.contains(OWNER_NAME) && l.contains("): hits on ") && l.contains(" shells, -");
            }
        } catch (Exception e) {
            System.out.println("[SVA-WORLDTEST] launch log: " + e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception e) {
                    // nothing to do
                }
            }
        }
        check(order && burst && hitsLine, "ad combat world: logs/airdefense.log (written in batches off the server thread) has the order with who gave it ("
                + order + "), the burst «assigned by Operator» (" + burst + "), the gun's hits summed up (" + hitsLine + ")");
    }

    private void nextC(World w, int k) {
        t2.setDead();
        g2.debugSettings(RoeMath.HOLD, false, false, 2);
        g3.debugSettings(RoeMath.FOREIGN_ALL, false, false, 2);
        g3.debugFireMode = true;                                    // audit review: whatever mode the other tests leave behind
        ammo3 = g3.ammo();
        t3 = plane(w, x0 + 22.5, Y + 10, z0 - 40.5, "Walled");
        scans();
        shellsVsWall(w);
        phase = 3;
        p0 = k;
    }

    // ---------------------------------------------------------------- C: walls

    /** A shell at t3 from in front of the wall (its 4-block steps never end inside it) and one over the wall. */
    private void shellsVsWall(World w) {
        double[] c = AirDefenseSVA.centre(t3);
        double sx = x0 + 22.5, sy = Y + 4.5, sz = z0 - 13.5;
        double[] d = BallisticsMath.dir(sx, sy, sz, c[0], c[1], c[2]);
        // start so that the 2nd step ends just before the wall (its block z0 - 22 .. z0 - 21): the 3rd ends past it - the old
        // end-block rule never saw it
        double k2 = (z0 - 20.95 - sz) / (d[2] * BallisticsMath.GUN_SPEED * 2);
        sx += d[0] * BallisticsMath.GUN_SPEED * 2 * (k2 - 1);
        sy += d[1] * BallisticsMath.GUN_SPEED * 2 * (k2 - 1);
        sz += d[2] * BallisticsMath.GUN_SPEED * 2 * (k2 - 1);
        d = BallisticsMath.dir(sx, sy, sz, c[0], c[1], c[2]);
        boolean endsMiss = true;
        for (int i = 1; i <= 10; i++) {
            int bx = (int) Math.floor(sx + d[0] * BallisticsMath.GUN_SPEED * i), by = (int) Math.floor(sy + d[1] * BallisticsMath.GUN_SPEED * i);
            int bz = (int) Math.floor(sz + d[2] * BallisticsMath.GUN_SPEED * i);
            endsMiss &= w.getBlock(bx, by, bz) != Blocks.stone;
        }
        int r1 = AirDefenseSVA.INSTANCE.debugFly((WorldServer) w, shell(sx, sy, sz, d), 30);
        double ox = x0 + 22.5, oy = Y + 14, oz = z0 - 13.5;
        int r2 = AirDefenseSVA.INSTANCE.debugFly((WorldServer) w, shell(ox, oy, oz, BallisticsMath.dir(ox, oy, oz, c[0], c[1], c[2])), 30);
        check(endsMiss && r1 == AirDefenseSVA.STOPPED && r2 == AirDefenseSVA.HIT,
                "ad combat world (bug 4): a shell whose step ends all miss the 1-block wall is stopped by it (" + r1 + "), one over the wall hits (" + r2 + ")");
    }

    private AirDefenseSVA.Projectile shell(double x, double y, double z, double[] d) {
        AirDefenseSVA.Projectile p = new AirDefenseSVA.Projectile();
        p.x = x;
        p.y = y;
        p.z = z;
        p.vx = d[0] * BallisticsMath.GUN_SPEED;
        p.vy = d[1] * BallisticsMath.GUN_SPEED;
        p.vz = d[2] * BallisticsMath.GUN_SPEED;
        p.life = BallisticsMath.SHELL_LIFE;
        p.target = t3 != null && !t3.isDead ? t3.getEntityId() : t5.getEntityId();
        p.dim = t3 != null ? t3.dimension : 0;
        p.damage = 0F;
        p.launcher = "";
        return p;
    }

    private void phaseC(World w, int k) {
        Integer id = Integer.valueOf(t3.getEntityId());
        long[] st = g3.engagements().get(id);
        if (fireAt3 < 0 && st != null && st[1] == RoeMath.ST_FIRE) {
            fireAt3 = k;
        }
        ready3 |= g3.debugReady(id.intValue());
        if (fireAt3 >= 0 && k - fireAt3 >= 40) {
            AirDefenseSVA.Contact c = kp.tracks().get(id);
            check(c != null && c.cls == RoeMath.FOREIGN && st != null && st[1] == RoeMath.ST_FIRE && ready3 && g3.ammo() == ammo3 && ammo3 > 0 && g3.aimLog().isEmpty(),
                    "ad combat world (bug 4): a gun at «fire» on a post's track it cannot see past a wall fires nothing - cleared for fire (rules, mode: "
                            + ready3 + "), only the wall stops it (ammunition " + ammo3 + " -> " + g3.ammo() + ")");
            if (c != null) {
                // audit review: «только вести» pressed between two rounds - the next burst in between is not fired
                boolean rd = g3.debugReady(id.intValue()), before = g3.debugHeldBetween(id.intValue());
                c.held = true;
                boolean after = g3.debugHeldBetween(id.intValue());
                c.held = false;
                check(rd && !before && after, "ad combat world (review): «только вести» on the post's track holds the fire between the 5-tick rounds too (ready "
                        + rd + ", before " + before + ", after " + after + ")");
            }
            nextD(w, k);
        } else if (k - p0 > 400) {
            check(false, "ad combat world (bug 4): the walled gun never reached «fire» (" + (st == null ? "not engaged" : "stage " + st[1]) + ")");
            nextD(w, k);
        }
    }

    private void nextD(World w, int k) {
        t3.setDead();
        g3.debugSettings(RoeMath.HOLD, false, false, 2);
        g3.debugFireMode = false;
        phase = 4;
        p0 = k;
        dStep = 0;
    }

    // ---------------------------------------------------------------- D: the terrain memory

    private boolean sees(World w) {
        r4.scan(RadarMath.TRACK);
        return r4.contacts().containsKey(Integer.valueOf(t5.getEntityId()));
    }

    private boolean eyesSee(World w) {
        for (AirDefenseSVA.Contact c : AirDefenseSVA.INSTANCE.eyes(w, dx + 16.5, Y + 2.5, dz + 4.5, BallisticsMath.EYES)) {
            if (c.id == t5.getEntityId()) {
                return true;
            }
        }
        return false;
    }

    private void phaseD(World w, int k) {
        ChunkProviderServer cp = ((WorldServer) w).theChunkProviderServer;
        if (dStep == 0) {
            check(!sees(w) && !eyesSee(w), "ad combat world (bug 10): the wall (loaded) hides the plane from the radar and the launcher's eyes");
            double y = Y + 3.5;
            boolean fence = !AirDefenseSVA.losClear(w, dx + 17.5, y, dz + 22.5, dx + 27.5, y, dz + 22.5);
            boolean slab = !AirDefenseSVA.losClear(w, dx + 17.5, Y + 3.25, dz + 29.5, dx + 27.5, Y + 3.25, dz + 29.5);
            boolean pane = AirDefenseSVA.losClear(w, dx + 17.5, Y + 3.5, dz + 34.5, dx + 27.5, Y + 3.5, dz + 34.5);
            boolean stairs = !AirDefenseSVA.losClear(w, dx + 17.5, Y + 3.5, dz + 38.5, dx + 27.5, Y + 3.5, dz + 38.5);
            check(fence && slab && stairs && pane, "ad combat world (bug 10): a row of fences / slabs / stairs stops a beam (" + fence + slab + stairs
                    + "), glass panes do not (" + pane + ")");
            int before = AdTerrainSVA.INSTANCE.remembered(w, dx + 5, dz + 10);
            for (int y2 = Y + 1; y2 <= Y + 30; y2++) {
                w.setBlock(dx + 5, y2, dz + 10, Blocks.stone, 0, 3);
            }
            check(before == Y + 1, "ad combat world (bug 10): the chunks round a new radar are remembered (" + before + ")");
            coverage(w);
            dStep = 1;
            p0 = k;
        } else if (dStep == 1 && k - p0 >= 30) {
            int now = AdTerrainSVA.INSTANCE.remembered(w, dx + 5, dz + 10);
            check(now == Y + 31, "ad combat world (bug 10): a pillar built by the radar - the memory read again (" + now + ")");
            cp.unloadChunksIfNotNearSpawn(c1[0], c1[1]);
            cp.unloadChunksIfNotNearSpawn(c2[0], c2[1]);
            unloadQueued = true;
            dStep = 2;
            p0 = k;
        } else if (dStep == 2) {
            boolean gone = !cp.chunkExists(c1[0], c1[1]) && !cp.chunkExists(c2[0], c2[1]);
            if (gone) {
                unloaded(w);
                dStep = 3;
                phase = 9;
            } else if (k - p0 > 60) {
                check(false, "ad combat world (bug 10): the wall's chunks never unloaded");
                phase = 9;
            }
        }
    }

    private void unloaded(World w) {
        double[] a = {r4.xCoord + 0.5, r4.yCoord + 1.5, r4.zCoord + 0.5}, c = AirDefenseSVA.centre(t5);
        boolean radar = sees(w), eyes = eyesSee(w), los = AirDefenseSVA.losClear(w, a[0], a[1], a[2], c[0], c[1], c[2]);
        boolean over = AirDefenseSVA.losClear(w, a[0], Y + 40, a[2], c[0], Y + 40, c[2]);
        check(!w.blockExists(dx + 40, Y + 5, dz) && !radar && !eyes && !los && over,
                "ad combat world (bug 10): the wall's chunk unloaded - it still hides the plane from the radar (" + radar + ") and the launcher's eyes (" + eyes
                        + "), a beam over it is clear (" + over + ")");
        int mem = AdTerrainSVA.INSTANCE.remembered(w, dx + 40, dz), cov = CoverageSVA.terrain(w).ground(dx + 40, dz);
        check(mem == Y + 21 && cov == Y + 21, "ad combat world (bug 10): the unloaded column remembered at the wall's top (" + mem + "), the coverage map knows it (" + cov + ")");
        double sx = dx + 30.5, sy = Y + 5, sz = dz + 0.5;
        AirDefenseSVA.Projectile p = shell(sx, sy, sz, BallisticsMath.dir(sx, sy, sz, c[0], c[1], c[2]));
        p.target = t5.getEntityId();
        p.dim = t5.dimension;
        int r = AirDefenseSVA.INSTANCE.debugFly((WorldServer) w, p, 30);
        check(r == AirDefenseSVA.STOPPED, "ad combat world (bug 10): a shell meets the unloaded wall (" + r + "), not the plane behind it");
    }

    /** A coverage map of the radar; an overdrive taken in - the kept map is stale (another reach), not handed out. */
    private void coverage(World w) {
        FakePlayer fp = operator(w);
        CoverageSVA.INSTANCE.request(fp, r4, 20);
        for (int i = 0; i < 40 && CoverageSVA.INSTANCE.grid(r4, 20) == null; i++) {
            CoverageSVA.INSTANCE.run(2000);
        }
        double[] b1 = CoverageSVA.INSTANCE.builtWith(r4, 20);
        double reach1 = CoverageSVA.reach(r4);
        r4.debugUpgrade(1, upgrade(UpgradeType.OVERDRIVE, 1));
        double reach2 = CoverageSVA.reach(r4);
        int st = CoverageSVA.INSTANCE.request(fp, r4, 20);
        for (int i = 0; i < 40 && CoverageSVA.INSTANCE.grid(r4, 20) == null; i++) {
            CoverageSVA.INSTANCE.run(2000);
        }
        double[] b2 = CoverageSVA.INSTANCE.builtWith(r4, 20);
        check(b1 != null && Math.abs(b1[3] - reach1) < 1e-6 && reach2 > reach1 && st == 1 && b2 != null && Math.abs(b2[3] - reach2) < 1e-6,
                "ad combat world: an overdrive taken in - the kept coverage map (reach " + Math.round(reach1) + ") is recomputed for the new reach (" + Math.round(reach2)
                        + "), not sent at the wrong scale (" + st + ")");
    }

    // ---------------------------------------------------------------- build

    private static ItemStack upgrade(UpgradeType type, int n) {
        ItemStack s = ModItems.upgrade.stackOf(type);
        s.stackSize = n;
        return s;
    }

    private static FakePlayer operator(World w) {
        return FakePlayerFactory.get((WorldServer) w, new GameProfile(UUID.nameUUIDFromBytes("sva-test:Operator".getBytes()), "Operator"));
    }

    private EntityVehicleSVA plane(World w, double x, double y, double z, String name) {
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.energy = st.maxEnergy();
        ItemStack is = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st);
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, is, x, y, z, 0F, STRANGER, name);
        if (v != null) {
            held.add(v);
            hold.add(new double[] {x, y, z});
            v.state.xpdr.dayKey = false;
            v.state.xpdr.mode = XpdrMath.A;
            v.state.xpdr.setCode(XpdrMath.VFR, w.getTotalWorldTime());
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private <T extends TileEntity> T block(World w, int x, int z, Block bl, int meta, Class<T> c) {
        BlockAirDefenseSVA.build(w, x, Y + 1, z, (BlockAirDefenseSVA) bl, meta, 2);
        TileEntity te = w.getTileEntity(x, Y + 1, z);
        if (te instanceof TileEntityAirDefenseSVA) {
            ((TileEntityAirDefenseSVA) te).setOwnerName(OWNER_NAME);
        }
        return c.isInstance(te) ? (T) te : null;
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = (sp.posX + 8800) >> 4 << 4;
        z0 = (sp.posZ - 8800) >> 4 << 4;
        dx = x0 + D_X;
        dz = z0;
        c1 = new int[] {(dx + 40) >> 4, (dz - 8) >> 4};
        c2 = new int[] {(dx + 40) >> 4, dz >> 4};
        keepLoaded(w);
        mode0 = ConfigSVA.adMode;
        minWarn0 = ConfigSVA.adMinWarningSeconds;
        pvp0 = ConfigSVA.adRequirePvp;
        unmanned0 = ConfigSVA.adFireOnUnmanned;
        dmg0 = ConfigSVA.adDamage;
        ConfigSVA.adMode = ConfigSVA.AD_FIRE;
        ConfigSVA.adMinWarningSeconds = 0;
        ConfigSVA.adRequirePvp = false;
        ConfigSVA.adFireOnUnmanned = true;
        ConfigSVA.adDamage = 0.02F;                                  // hit many times, never a wreck
        w.getWorldInfo().setRaining(false);
        w.getWorldInfo().setThundering(false);
        area(w, true);
        kp = block(w, x0, z0 - 4, ModContentSVA.commandPost, 2, TileEntityCommandPostSVA.class);
        survey = block(w, x0 + 3, z0 - 4, ModContentSVA.radar, RadarMath.SURVEY, TileEntityRadarSVA.class);
        track = block(w, x0 + 9, z0 - 4, ModContentSVA.radar, RadarMath.TRACK, TileEntityRadarSVA.class);
        g = block(w, x0, z0 + 4, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        g2 = block(w, x0 + 4, z0 + 4, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        g3 = block(w, x0 + 22, z0 - 14, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        r4 = block(w, dx, dz, ModContentSVA.radar, RadarMath.TRACK, TileEntityRadarSVA.class);
        check(kp != null && survey != null && track != null && g != null && g2 != null && g3 != null && r4 != null,
                "ad combat world: the command post, three radars and three guns placed");
        if (kp == null || survey == null || track == null || g == null || g2 == null || g3 == null || r4 == null) {
            kp = null;
            return;
        }
        for (TileEntityLauncherSVA l : new TileEntityLauncherSVA[] {g, g2, g3}) {
            l.setKpLink(kp.key());
            for (int i = 0; i < 3; i++) {
                l.debugAmmo(ModContentSVA.ammo.stackOf(ItemAmmoSVA.SHELLS, 64));
            }
        }
        survey.setKpLink(kp.key());
        track.setKpLink(kp.key());
        g.debugSettings(RoeMath.FOREIGN_ALL, false, false, 2);
        g2.debugSettings(RoeMath.FOREIGN_ALL, false, false, 2);
        g3.debugSettings(RoeMath.HOLD, false, false, 2);
        ammoA = g.ammo();
        ammoA2 = g2.ammo();
        // the walls: C (in front of the gun g3, 1 block thick, out of the other guns' way) and D (in the chunk that is unloaded
        // later); D's rows
        for (int x = x0 + 18; x <= x0 + 27; x++) {
            for (int y = Y + 1; y <= Y + 8; y++) {
                w.setBlock(x, y, z0 - 22, Blocks.stone, 0, 2);
            }
        }
        for (int z = dz - 8; z <= dz + 8; z++) {
            for (int y = Y + 1; y <= Y + 20; y++) {
                w.setBlock(dx + 40, y, z, Blocks.stone, 0, 2);
                w.setBlock(dx + 41, y, z, Blocks.stone, 0, 2);
            }
        }
        for (int z = dz + 20; z <= dz + 25; z++) {
            for (int y = Y + 1; y <= Y + 6; y++) {
                w.setBlock(dx + 22, y, z, Blocks.fence, 0, 2);
            }
        }
        for (int z = dz + 28; z <= dz + 31; z++) {
            w.setBlock(dx + 22, Y + 3, z, Blocks.stone_slab, 0, 2);
        }
        for (int z = dz + 33; z <= dz + 36; z++) {
            w.setBlock(dx + 22, Y + 3, z, Blocks.glass_pane, 0, 2);
        }
        for (int z = dz + 37; z <= dz + 40; z++) {
            w.setBlock(dx + 22, Y + 3, z, Blocks.stone_stairs, 0, 2);
        }
        // the moving plane, the plane behind D's wall
        double[] p = path(0);
        m = plane(w, p[0], FLY, p[1], "Crosser");
        t5 = plane(w, dx + 60.5, Y + 10, dz + 0.5, "Hidden");
        if (m == null || t5 == null) {
            check(false, "ad combat world: the test planes placed");
            kp = null;
            return;
        }
        held.remove(m);                                             // it moves on its own path (tick)
        hold.remove(0);
        modeC(w);
        rwr(w);
    }

    /** Mode C's altitude from the plane's centre, as the radars measure it. */
    private void modeC(World w) {
        long now = w.getTotalWorldTime();
        AirDefenseSVA.Contact k = new AirDefenseSVA.Contact(m.getEntityId());
        AirDefenseSVA.INSTANCE.fill(k, m, m.posX, m.posY, m.posZ, false, now);
        m.state.xpdr.mode = XpdrMath.C;
        AirDefenseSVA.INSTANCE.interrogate(k, m, now);
        m.state.xpdr.mode = XpdrMath.A;
        check(k.replied && k.altKnown && Math.abs(k.y - AirDefenseSVA.centre(m)[1]) < 1e-6 && k.altAt == now,
                "ad combat world: Mode C's altitude from the aircraft's centre (" + Math.round(k.y * 10) / 10.0 + ", its belly " + Math.round(m.posY * 10) / 10.0 + ")");
    }

    /** The RWR's list of a fresh plane: 16 expired paints, then 16 live ones - a new paint is on it each time. */
    private void rwr(World w) {
        EntityVehicleSVA v = plane(w, dx + 5.5, Y + 60, dz + 30.5, "Painted");
        if (v == null) {
            check(false, "ad combat world: the RWR plane placed");
            return;
        }
        long now = w.getTotalWorldTime();
        for (int i = 0; i < RadarMath.PAINT_CAP; i++) {
            AirDefenseSVA.INSTANCE.paint(v, RadarMath.T_SEARCH, 1e6 + i * 10, 0, now - 1);
        }
        AirDefenseSVA.INSTANCE.paint(v, RadarMath.T_LOCK, 2e6, 0, now + 100);
        boolean a = false;
        for (double[] th : AirDefenseSVA.INSTANCE.threats(v)) {
            a |= th[1] == 2e6;
        }
        for (int i = 0; i < RadarMath.PAINT_CAP; i++) {
            AirDefenseSVA.INSTANCE.paint(v, RadarMath.T_SEARCH, 3e6 + i * 10, 0, now + 200 + i);
        }
        AirDefenseSVA.INSTANCE.paint(v, RadarMath.T_LOCK, 4e6, 0, now + 150);
        boolean b = false;
        int n = 0;
        for (double[] th : AirDefenseSVA.INSTANCE.threats(v)) {
            b |= th[1] == 4e6;
            n++;
        }
        check(a && b && n == RadarMath.PAINT_CAP, "ad combat world (bug 16): the RWR list - the 17th emitter's paint gets on after 16 expired ones and after 16 live ones ("
                + n + " kept)");
        held.remove(v);
        hold.remove(hold.size() - 1);
        v.setDead();
    }

    private void area(World w, boolean build) {
        // site A (the post, the radars, the guns, wall C)
        for (int x = x0 - 6; x <= x0 + 30; x++) {
            for (int z = z0 - 24; z <= z0 + 14; z++) {
                for (int y = Y + 1; y <= Y + 10; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
        // site D (the radar's floor; the walls and rows are cleared by finish)
        for (int x = dx - 3; x <= dx + 8; x++) {
            for (int z = dz - 3; z <= dz + 12; z++) {
                for (int y = Y + 1; y <= Y + 31; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private void keepLoaded(World w) {
        if (!(w instanceof WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        ChunkProviderServer cp = ((WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 100) >> 4; cx <= (x0 + 110) >> 4; cx++) {
            for (int cz = (z0 - 50) >> 4; cz <= (z0 + 60) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
        for (int cx = (dx - 16) >> 4; cx <= (dx + 80) >> 4; cx++) {
            for (int cz = (dz - 24) >> 4; cz <= (dz + 44) >> 4; cz++) {
                boolean wall = cx == c1[0] && (cz == c1[1] || cz == c2[1]);
                if (!(unloadQueued && wall)) {
                    cp.loadChunk(cx, cz);                           // the wall's chunks left to unload
                }
            }
        }
    }

    private void finish(World w) {
        ConfigSVA.adMode = mode0 == null ? ConfigSVA.AD_WARNINGS : mode0;
        ConfigSVA.adMinWarningSeconds = minWarn0;
        ConfigSVA.adRequirePvp = pvp0;
        ConfigSVA.adFireOnUnmanned = unmanned0;
        ConfigSVA.adDamage = dmg0;
        unloadQueued = false;
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {m, t2, t3, t5}) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        for (EntityVehicleSVA v : held) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        held.clear();
        hold.clear();
        for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kp, survey, track, g, g2, g3, r4}) {
            if (te != null) {
                te.noDrop = true;                                   // cleared below: no items left lying about
            }
        }
        if (x0 != 0 || z0 != 0) {
            keepLoaded(w);
            for (int x = x0 + 18; x <= x0 + 27; x++) {
                for (int y = Y + 1; y <= Y + 8; y++) {
                    w.setBlockToAir(x, y, z0 - 22);
                }
            }
            for (int z = dz - 8; z <= dz + 8; z++) {
                for (int y = Y + 1; y <= Y + 20; y++) {
                    w.setBlockToAir(dx + 40, y, z);
                    w.setBlockToAir(dx + 41, y, z);
                }
            }
            for (int z = dz + 20; z <= dz + 40; z++) {
                for (int y = Y + 1; y <= Y + 6; y++) {
                    w.setBlockToAir(dx + 22, y, z);
                }
            }
            area(w, false);
        }
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
        if (dd != null && kp != null) {
            dd.removeKp(kp.key());
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
}
