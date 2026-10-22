package com.sva.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.sc.init.ModItems;
import com.sc.machine.UpgradeType;
import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.AirportCodeMath;
import com.sva.airdefense.BallisticsMath;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.block.BlockAdPartSVA;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.ItemBlockAirDefenseSVA;
import com.sva.block.TileEntityAdPartSVA;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityCommandPostSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemAmmoSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.net.NetADSVA;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Audit 2026-10-22 world test (docs, tests and consistency - the gaps of the report's sections 5 and 7.5), run by WorldTestSVA
 * alongside its other phases from tick START in its own area (x spawn + 10800, z spawn - 10800, stone pads at Y 150),
 * owner «TestADO»: a powered command post with a surveillance radar and two AA guns on «по отметкам КП» round a forbidden
 * zone, strangers' planes held in it.
 * <ul>
 * <li>the post's command() (the screen's operations): «set» - own codes, the Mode S white list (bound to its owner, a wrong
 * owner, a bare number bound to the owner seen with it), trusted towers (bound «ICAO:Owner» trusted only with that owner, an
 * unbound one trusts nobody); «secret» - the owner's day code; «assign» to a launcher and «auto» (the nearest), who gave
 * the order; the manual alarm on / off (redstone 15 / 0); «msg»; «track» (hold fire - the assignment goes); «Авто» on (a
 * launcher on «по отметкам КП» takes a violator by itself) and off; «grant» (the engaged one let go) - and the classes
 * the post gives (own code, white list, 7700, the day code);</li>
 * <li>NBT round trips: the command post and the launcher (written, read into a fresh tile, written again - the same tag),
 * AirDefenseDataSVA (zones per dimension, the nodes the limits count, the trust data, the owner's secret);</li>
 * <li>NetADSVA: the real picture of the post (and the launcher's) through a byte buffer and back, at its biggest too (16
 * zones, 40 log lines);</li>
 * <li>7.4: Shift+RMB with shells on the gun's master and on one of its parts through the player's real path
 * (ItemInWorldManager - Forge's sneak rule) loads it; the part's description packet gives a fresh (client) tile its master;
 * the part's pick-block is the launcher;</li>
 * <li>airdefense.damageMultiplier x1 / x0.5 / x0 on a shell's hit, and vehicles.wear off - no damage at all;</li>
 * <li>the per-player limits (launchers, radars; 0 - none; another player, the command post, creative not counted; the
 * item's placeBlockAt refused); a broken launcher frees its place;</li>
 * <li>airdefense.enabled=false (flipped and put back within one call, the site's blocks ticked by hand meanwhile): every
 * block unpowered, no tracks, no engagements, the alarm off; on again - powered.</li>
 * </ul>
 * Global settings it changes for a moment (the damage, the wear, the limits) are set and put back inside one call.
 */
final class WorldTestAdOpsSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000ade0", OWNER_NAME = "TestADO";
    private static final String STRANGER = "00000000-5bad-4e57-0000-00000000ade1";
    private static final String SECRET = "opsKey", ICAO_BOUND = "QZOP", ICAO_FREE = "QZOQ";
    private static final int OWN_CODE = 01234;
    static final int START = 2900;
    private static final int Y = 150, FLY = Y + 20, MAX_TICKS = 7000, STEP = 40;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0, stage, stageAt = -1;
    boolean done;
    private TileEntityCommandPostSVA kp;
    private TileEntityRadarSVA radar;
    private TileEntityLauncherSVA g1, g2;
    private EntityVehicleSVA s1, s2, s3, pOwn, pWhite, pWrong, pBare, p7700, pDay, pDmg;
    private EntityVehicleSVA granted;
    private final List<EntityVehicleSVA> planes = new ArrayList<EntityVehicleSVA>();
    private final List<double[]> hold = new ArrayList<double[]>();
    private List<ZoneMath.Zone> zones0;
    private FakePlayer op;
    private boolean secretSet, disabled;

    WorldTestAdOpsSVA(WorldTestSVA t) {
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
        }
        int k = ticks - t0;
        if (phase == 1) {
            for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kp, radar, g1, g2}) {
                if (te != null && !te.isInvalid()) {
                    te.fillForTest();
                }
            }
            holdPlanes(w);
            run(w, k);
            if (phase == 1 && k > MAX_TICKS) {
                check(false, "ad ops world: every stage finished in time (stuck at stage " + stage + ")");
                phase = 9;
            }
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private void holdPlanes(World w) {
        long now = w.getTotalWorldTime();
        for (int i = 0; i < planes.size(); i++) {
            EntityVehicleSVA v = planes.get(i);
            double[] p = hold.get(i);
            if (v != null && !v.isDead) {
                v.setPosition(p[0], p[1], p[2]);
                v.motionX = v.motionY = v.motionZ = 0;
            }
        }
        if (secretSet && pDay != null) {
            pDay.state.xpdr.setCode(XpdrMath.dayCode(SECRET, XpdrMath.day(w.getWorldTime())), now);     // a new game day mid-test too
        }
    }

    private boolean after(int k) {
        return stageAt >= 0 && k - stageAt >= STEP;
    }

    private void next(int k) {
        stage++;
        stageAt = k;
    }

    private void run(World w, int k) {
        switch (stage) {
            case 0:
                if (k >= 40 && pictureReady()) {
                    commands1(w);
                    next(k);
                } else if (k > 1200) {
                    check(false, "ad ops world: the post never had every plane's answered track");
                    phase = 9;
                }
                break;
            case 1:
                // the day code is read at the surveillance radar's next sweep (6 s): wait for it, then the classes
                if (after(k) && (dayHeard(w) || k - stageAt > 400)) {
                    checks1(w);
                    commands2(w);
                    next(k);
                }
                break;
            case 2:
                if (after(k)) {
                    checks2(w);
                    commands3(w);
                    next(k);
                }
                break;
            case 3:
                if (after(k)) {
                    checks3(w);
                    roundTrips(w);
                    ammo(w);
                    pickBlock(w);
                    damage(w);
                    limits(w);
                    disabledSync(w);
                    netStress(w);
                    freed(w);
                    phase = 9;
                }
                break;
            default:
                phase = 9;
        }
    }

    // ---------------------------------------------------------------- helpers

    private AirDefenseSVA.Contact track(EntityVehicleSVA v) {
        return v == null ? null : kp.tracks().get(Integer.valueOf(v.getEntityId()));
    }

    private int cls(EntityVehicleSVA v) {
        AirDefenseSVA.Contact c = track(v);
        return c == null ? -1 : c.cls;
    }

    private static boolean eng(TileEntityLauncherSVA l, EntityVehicleSVA v) {
        return v != null && l.engagements().containsKey(Integer.valueOf(v.getEntityId()));
    }

    private boolean pictureReady() {
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {s1, s2, s3, pOwn, pWhite, pWrong, pBare, p7700, pDay}) {
            AirDefenseSVA.Contact c = track(v);
            if (c == null || !c.replied || c.askedAt < 0) {
                return false;
            }
        }
        return true;
    }

    /** The post's track of the day-code plane answers today's code (the radar has interrogated it since the code was set). */
    private boolean dayHeard(World w) {
        AirDefenseSVA.Contact c = track(pDay);
        return c != null && c.replied && c.code == XpdrMath.dayCode(SECRET, XpdrMath.day(w.getWorldTime()));
    }

    private AirDefenseDataSVA.KpInfo info(World w) {
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        return d == null ? null : d.info(kp.key(), false);
    }

    private void cmd(FakePlayer p, String opName, EntityVehicleSVA v, String key, String val) {
        NBTTagCompound d = new NBTTagCompound();
        d.setString("Op", opName);
        if (v != null) {
            d.setInteger("Id", v.getEntityId());
        }
        if (key != null) {
            d.setString(key, val);
        }
        kp.command(p, d);
    }

    private int logged(String key) {
        int n = 0;
        for (String[] l : kp.logLines()) {
            n += l.length > 1 && l[1].equals(key) ? 1 : 0;
        }
        return n;
    }

    private int redstone(World w) {
        return kp.getBlockType().isProvidingWeakPower(w, kp.xCoord, kp.yCoord, kp.zCoord, 0);
    }

    // ---------------------------------------------------------------- the post's commands

    private void commands1(World w) {
        // «Настройки»: own codes, the Mode S white list, trusted towers
        NBTTagCompound d = new NBTTagCompound();
        d.setString("Op", "set");
        d.setString("Name", "OPS");
        d.setIntArray("Codes", new int[] {OWN_CODE});
        d.setIntArray("WB", new int[] {pWhite.getBoardNumber(), pWrong.getBoardNumber(), pBare.getBoardNumber()});
        NBTTagList wo = new NBTTagList();
        wo.appendTag(new NBTTagString("WhiteOwner"));
        wo.appendTag(new NBTTagString("SomeoneElse"));
        wo.appendTag(new NBTTagString(""));
        d.setTag("WO", wo);
        d.setString("Trust", ICAO_BOUND + ":Bob; " + ICAO_FREE);
        kp.command(null, d);
        AirDefenseDataSVA.KpInfo i = info(w);
        boolean clash = pBare.getBoardNumber() == pWhite.getBoardNumber() || pBare.getBoardNumber() == pWrong.getBoardNumber();
        check(i != null && "OPS".equals(i.name) && i.ownCode(OWN_CODE) && !i.ownCode(07000) && i.whitelisted(pWhite.getBoardNumber(), "WhiteOwner")
                && !i.whitelisted(pWrong.getBoardNumber(), "WrongOwner") && (clash || i.whitelisted(pBare.getBoardNumber(), "BareOwner"))
                && i.trusted.contains(ICAO_BOUND + ":Bob") && i.trusted.contains(ICAO_FREE),
                "ad ops world (KP «set»): own codes, the white list - an entry with its owner, a wrong owner, a bare number bound to the owner seen "
                        + "with it" + (clash ? " (two boards share the number - it stays unbound)" : "") + ", trusted towers - in the post's data");
        check(kp.friendlyAirport("Bob", ICAO_BOUND) && !kp.friendlyAirport("Mallory", ICAO_BOUND) && !kp.friendlyAirport("Mallory", ICAO_FREE)
                && !kp.friendlyAirport("", ICAO_FREE) && AirportCodeMath.trustedTower(i == null ? null : i.trusted, ICAO_BOUND, "bob"),
                "ad ops world (KP «set», bug 7): a trusted tower «ICAO:Owner» counts only with that owner; an ICAO with no tower to bind it trusts nobody");
        lateBinding(w, d);
        // «Секрет»: the owner's one day code
        op = player(w, OWNER_NAME);
        cmd(op, "secret", null, "S", SECRET);
        AirDefenseDataSVA dd = AirDefenseDataSVA.get(w);
        int dc = XpdrMath.dayCode(SECRET, XpdrMath.day(w.getWorldTime()));
        check(dd != null && SECRET.equals(dd.secret(OWNER_NAME)) && kp.dayCode() == dc,
                "ad ops world (KP «secret»): the owner's secret set from the screen - the post's day code " + XpdrMath.format(dc));
        secretSet = true;
        holdPlanes(w);
        // «Назначить»: one to a named launcher, one to «авто» (the nearest powered launcher in reach)
        cmd(op, "assign", s1, "L", g1.key());
        AirDefenseSVA.Contact c2 = track(s2);
        TileEntityLauncherSVA near = null;
        double best = Double.MAX_VALUE;
        for (TileEntityLauncherSVA l : new TileEntityLauncherSVA[] {g1, g2}) {
            double dist = l.getDistanceFrom(c2.x, c2.y, c2.z);
            if (l.isPowered() && dist < best && Math.sqrt(dist) <= l.maxRange()) {
                best = dist;
                near = l;
            }
        }
        cmd(op, "assign", s2, "L", "auto");
        AirDefenseDataSVA.Asg a1 = i == null ? null : i.assigned.get(AirDefenseSVA.vehKey(s1));
        check(kp.assignedTo(s1.getEntityId(), g1) && !kp.assignedTo(s1.getEntityId(), g2) && a1 != null && a1.launcher.equals(g1.key())
                && OWNER_NAME.equals(a1.by) && near == g2 && kp.assignedTo(s2.getEntityId(), g2) && logged("sva.ad.log.assigned") >= 2,
                "ad ops world (KP «assign»): a track to a named launcher (who gave it - kept), another to «авто» - the nearest powered launcher in reach ("
                        + kp.assignedTo(s1.getEntityId(), g1) + " " + (a1 == null ? "-" : a1.by) + " " + (near == g2) + " " + kp.assignedTo(s2.getEntityId(), g2)
                        + ", altitude known " + c2.altKnown + ")");
        // the manual alarm: on (redstone 15), a message to the pilot
        cmd(op, "alarm", null, null, null);
        cmd(op, "msg", s3, "T", "Покиньте зону");
        check(kp.isAlarm() && redstone(w) == 15 && logged("sva.ad.log.alarm") >= 1 && logged("sva.ad.log.msg") >= 1,
                "ad ops world (KP «alarm», «msg»): the manual alarm on - the post's redstone 15, in the log; the message to the pilot logged");
    }

    /**
     * Audit review 2026-10-22 (bug 7): Mallory takes the ICAO the post kept waiting bare - a later save of the settings (the
     * same text sent back, as the screen does) no longer binds it to her; typed anew it is bound (the chat says to whom).
     */
    private void lateBinding(World w, NBTTagCompound d) {
        com.sva.block.AirportDataSVA ad = com.sva.block.AirportDataSVA.get(w);
        if (ad == null) {
            check(false, "ad ops world (review, bug 7): no airport data");
            return;
        }
        com.sva.block.AirportDataSVA.Tower tw = new com.sva.block.AirportDataSVA.Tower();
        tw.key = "0:" + (x0 + 300) + ":1:" + (z0 + 300);
        tw.icao = ICAO_FREE;
        tw.owner = "Mallory";
        ad.putTower(tw);
        kp.command(null, d);                                        // the same settings saved again (a name change, say)
        boolean kept = kp.debugTrusted().contains(ICAO_FREE) && !kp.friendlyAirport("Mallory", ICAO_FREE);
        NBTTagCompound d2 = (NBTTagCompound) d.copy();
        d2.setString("Trust", ICAO_BOUND + ":Bob");
        kp.command(null, d2);                                       // removed ...
        d2.setString("Trust", ICAO_BOUND + ":Bob, " + ICAO_FREE);
        kp.command(null, d2);                                       // ... and typed in again: bound now, told
        boolean typed = kp.debugTrusted().contains(ICAO_FREE + ":Mallory") && kp.friendlyAirport("Mallory", ICAO_FREE);
        ad.remove(tw.key);
        kp.command(null, d);                                        // back as the test had it (unbound - no tower)
        check(kept && typed && kp.debugTrusted().contains(ICAO_FREE) && !kp.friendlyAirport("Mallory", ICAO_FREE),
                "ad ops world (review, bug 7): a bare ICAO kept waiting is not bound to the one who took it since by a later save (" + kept
                        + "); typed anew it is (" + typed + ")");
    }

    private void checks1(World w) {
        boolean clash = pBare.getBoardNumber() == pWhite.getBoardNumber() || pBare.getBoardNumber() == pWrong.getBoardNumber();
        check(cls(pOwn) == RoeMath.FRIEND && cls(pWhite) == RoeMath.FRIEND && cls(pWrong) == RoeMath.VIOLATOR
                && cls(pBare) == (clash ? RoeMath.VIOLATOR : RoeMath.FRIEND) && cls(p7700) == RoeMath.EMERGENCY && cls(pDay) == RoeMath.FRIEND
                && cls(s1) == RoeMath.VIOLATOR && cls(s3) == RoeMath.VIOLATOR,
                "ad ops world (KP classes): an own code, the white list with its owner, the bound bare number, the day code - friends; the wrong owner - "
                        + "a violator; 7700 - emergency (" + cls(pOwn) + cls(pWhite) + cls(pWrong) + cls(pBare) + cls(p7700) + cls(pDay) + ")");
        boolean friendsFree = true;
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {pOwn, pWhite, pDay, p7700}) {
            friendsFree &= !eng(g1, v) && !eng(g2, v);
        }
        check(eng(g1, s1) && eng(g2, s2) && !eng(g1, s3) && !eng(g2, s3) && friendsFree && kp.isAlarm(),
                "ad ops world (KP «assign»): each gun on «по отметкам КП» works its assigned track, an unassigned violator is left (no «Авто»), friends never; "
                        + "the manual alarm stays on (" + g1.engagements().keySet() + " / " + g2.engagements().keySet() + ")");
        cmd(op, "alarm", null, null, null);
        boolean off = !kp.isAlarm() && redstone(w) == 0 && logged("sva.ad.log.alarmOff") >= 1;
        cmd(op, "track", s1, null, null);
        AirDefenseDataSVA.KpInfo i = info(w);
        check(off && !kp.assignedTo(s1.getEntityId(), g1) && kp.heldFire(s1.getEntityId()) && i != null && i.held(AirDefenseSVA.vehKey(s1), w.getTotalWorldTime())
                && !i.assigned.containsKey(AirDefenseSVA.vehKey(s1)) && logged("sva.ad.log.trackOnly") >= 1,
                "ad ops world (KP «alarm» off, «track»): the alarm off - redstone 0; «только вести» ends the assignment and holds the fire");
    }

    private void commands2(World w) {
        cmd(op, "auto", null, null, null);
        check(kp.isAuto(), "ad ops world (KP «auto»): «Авто» on");
    }

    private void checks2(World w) {
        AirDefenseSVA.Contact c1 = track(s1);
        EntityVehicleSVA took = null;
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {s3, pWrong, pBare}) {          // s2 (assigned to g2) is far from g1
            if (eng(g1, v) && cls(v) == RoeMath.VIOLATOR) {
                took = v;
            }
        }
        check(!eng(g1, s1) && !eng(g2, s1) && c1 != null && c1.held && took != null && eng(g2, s2),
                "ad ops world (KP «track», «auto»): the held track let go by both guns; with «Авто» the free gun takes an unassigned violator itself ("
                        + g1.engagements().keySet() + ")");
        granted = took;
    }

    private void commands3(World w) {
        if (granted != null) {
            cmd(op, "grant", granted, null, null);
        }
        cmd(op, "auto", null, null, null);
        AirDefenseDataSVA.KpInfo i = info(w);
        check(granted != null && i != null && i.granted(AirDefenseSVA.vehKey(granted), w.getTotalWorldTime()) && !kp.isAuto() && logged("sva.ad.log.grant") >= 1,
                "ad ops world (KP «grant», «auto» off): «свой на 10 минут» kept by the vehicle's persistent id; «Авто» off");
    }

    private void checks3(World w) {
        boolean gone = granted != null && cls(granted) == RoeMath.FRIEND && !eng(g1, granted) && !eng(g2, granted);
        check(gone && g1.engagements().isEmpty() && eng(g2, s2),
                "ad ops world (KP «grant», «auto» off): the granted one is a friend and let go; with «Авто» off the gun with no assignment stands down ("
                        + g1.engagements().keySet() + "), the assigned one keeps working");
    }

    /**
     * Audit review 2026-10-22: a post saved before the owner binding (no «KpTrustV»): its bare trusted ICAO is bound to the
     * tower's owner at the first tick, its bare white-list number keeps protecting any owner until the settings are saved
     * (both silently stopped protecting after the update); both into the post's log.
     */
    private void legacy(World w, NBTTagCompound saved) {
        com.sva.block.AirportDataSVA ad = com.sva.block.AirportDataSVA.get(w);
        NBTTagCompound old = (NBTTagCompound) saved.copy();
        old.removeTag("KpTrustV");
        NBTTagList wo = new NBTTagList();
        wo.appendTag(new NBTTagString("WhiteOwner"));
        wo.appendTag(new NBTTagString(""));
        wo.appendTag(new NBTTagString(""));
        old.setTag("KpWO", wo);
        NBTTagList tl = new NBTTagList();
        tl.appendTag(new NBTTagString("QZOR"));
        tl.appendTag(new NBTTagString(ICAO_FREE));
        old.setTag("KpTrust", tl);
        com.sva.block.AirportDataSVA.Tower tw = new com.sva.block.AirportDataSVA.Tower();
        tw.key = "0:" + (x0 + 310) + ":1:" + (z0 + 310);
        tw.icao = "QZOR";
        tw.owner = "Carol";
        if (ad != null) {
            ad.putTower(tw);
        }
        TileEntityCommandPostSVA k3 = new TileEntityCommandPostSVA();
        k3.readFromNBT(old);
        int bw = pWrong.getBoardNumber();
        boolean clashW = pWhite.getBoardNumber() == bw || pWhite.getBoardNumber() == pBare.getBoardNumber();
        boolean anyBefore = k3.whitelisted(bw, "Anyone") && k3.whitelisted(pWhite.getBoardNumber(), "WhiteOwner")
                && (clashW || !k3.whitelisted(pWhite.getBoardNumber(), "Anyone"));
        k3.setWorldObj(w);
        k3.debugMigrate();
        boolean logged = false, loggedW = false;
        for (String[] l : k3.logLines()) {
            logged |= l.length > 1 && l[1].equals("sva.ad.log.legacyTrust");
            loggedW |= l.length > 1 && l[1].equals("sva.ad.log.legacyWhite");
        }
        boolean bound = k3.debugTrusted().contains("QZOR:Carol") && k3.debugTrusted().contains(ICAO_FREE) && k3.friendlyAirport("Carol", "QZOR");
        NBTTagCompound again = new NBTTagCompound();
        k3.writeToNBT(again);
        TileEntityCommandPostSVA k4 = new TileEntityCommandPostSVA();
        k4.readFromNBT(again);
        boolean kept = k4.whitelisted(bw, "Anyone") && again.hasKey("KpTrustV");
        if (ad != null) {
            ad.remove(tw.key);
        }
        check(anyBefore && bound && logged && loggedW && kept,
                "ad ops world (review): an older save's post - its bare trusted ICAO bound to the tower's owner (" + bound + "), its bare white-list "
                        + "number for any owner until saved (" + anyBefore + ", kept through a save " + kept + "), both in its log (" + logged + loggedW + ")");
    }

    // ---------------------------------------------------------------- NBT and the wire

    private static NetADSVA.Data wire(NetADSVA.Data m, int[] size) {
        ByteBuf b = Unpooled.buffer();
        m.toBytes(b);
        size[0] = b.readableBytes();
        NetADSVA.Data r = new NetADSVA.Data();
        r.fromBytes(b);
        return b.readableBytes() == 0 ? r : null;
    }

    private void roundTrips(World w) {
        // the command post
        NBTTagCompound a = new NBTTagCompound();
        kp.writeToNBT(a);
        TileEntityCommandPostSVA k2 = new TileEntityCommandPostSVA();
        k2.readFromNBT(a);
        NBTTagCompound b = new NBTTagCompound();
        k2.writeToNBT(b);
        int[] codes = b.getIntArray("KpCodes");
        boolean own = false;
        for (int c : codes) {
            own |= c == OWN_CODE;
        }
        check(a.equals(b) && own && "OPS".equals(b.getString("KpName")) && SECRET.equals(b.getString("KpSecret")) && b.getTagList("KpZones", 10).tagCount() == 1
                && b.getTagList("KpTrust", 8).tagCount() == 2 && b.getIntArray("KpWB").length == 3 && b.getTagList("KpLog", 10).tagCount() >= 6
                && OWNER_NAME.equals(b.getString("AdOwner")),
                "ad ops world (NBT): the command post written, read into a fresh tile, written again - the same (name, secret, codes, white list, trusted, "
                        + "zones, log, owner)");
        legacy(w, a);
        // the launcher (a module in, its ammunition, its rule, its link)
        g1.debugUpgrade(0, ModItems.upgrade.stackOf(UpgradeType.OVERCLOCKER));
        a = new NBTTagCompound();
        g1.writeToNBT(a);
        TileEntityLauncherSVA l2 = new TileEntityLauncherSVA();
        l2.readFromNBT(a);
        b = new NBTTagCompound();
        l2.writeToNBT(b);
        check(a.equals(b) && b.getInteger("LRoe") == RoeMath.KP_MARKS && kp.key().equals(b.getString("AdKpLink")) && l2.ammo() == g1.ammo() && l2.ammo() > 0
                && b.getTagList("AdUpg", 10).tagCount() == 1 && l2.roe() == RoeMath.KP_MARKS,
                "ad ops world (NBT): the launcher written and read back - its rule, its post's link, ammunition " + l2.ammo() + ", the module");
        // the world's air-defence data: once read, it writes the same again; zones per dimension, nodes, trust data, secret
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        NBTTagCompound t0 = new NBTTagCompound();
        d.writeToNBT(t0);
        AirDefenseDataSVA r1 = new AirDefenseDataSVA("svaOpsTest1");
        r1.readFromNBT(t0);
        List<ZoneMath.Zone> other = new ArrayList<ZoneMath.Zone>();
        List<ZoneMath.Zone> mine = d.ofOwner(OWNER_NAME, 0);
        if (!mine.isEmpty()) {
            ZoneMath.Zone z = mine.get(0).copy();                   // as the post published it (its owner, position, dimension)
            z.dim = 1;
            other.add(z);
        }
        r1.setZones(AirDefenseDataSVA.key(1, x0, Y + 1, z0), other);     // the same owner's post in the End
        NBTTagCompound t1 = new NBTTagCompound();
        r1.writeToNBT(t1);
        AirDefenseDataSVA r2 = new AirDefenseDataSVA("svaOpsTest2");
        r2.readFromNBT(t1);
        NBTTagCompound t2 = new NBTTagCompound();
        r2.writeToNBT(t2);
        AirDefenseDataSVA.KpInfo i = r2.info(kp.key(), false);
        boolean infoOk = i != null && i.ownCode(OWN_CODE) && i.whitelisted(pWhite.getBoardNumber(), "WhiteOwner") && i.trusted.contains(ICAO_BOUND + ":Bob")
                && granted != null && i.granted(AirDefenseSVA.vehKey(granted), w.getTotalWorldTime()) && i.held(AirDefenseSVA.vehKey(s1), w.getTotalWorldTime())
                && i.assigned.containsKey(AirDefenseSVA.vehKey(s2));
        boolean nodes = r2.count(OWNER_NAME, AirDefenseDataSVA.N_LAUNCHER) == 2 && r2.count(OWNER_NAME, AirDefenseDataSVA.N_RADAR) == 1
                && r2.count(OWNER_NAME, AirDefenseDataSVA.N_LAUNCHER) == d.count(OWNER_NAME, AirDefenseDataSVA.N_LAUNCHER);
        boolean dims = r2.ofOwner(OWNER_NAME, 0).size() == 1 && r2.ofOwner(OWNER_NAME, 1).size() == 1 && r2.ofOwner(OWNER_NAME, -1).isEmpty();
        check(t1.equals(t2) && nodes && dims && SECRET.equals(r2.secret(OWNER_NAME)) && infoOk,
                "ad ops world (NBT): AirDefenseDataSVA through the save and back - the nodes the limits count, the zones by dimension, the post's trust "
                        + "data (codes, white list, trusted, «свой», «только вести», assignments), the owner's secret (stable " + t1.equals(t2) + ", nodes " + nodes
                        + ", dims " + dims + ", secret " + SECRET.equals(r2.secret(OWNER_NAME)) + ", info " + infoOk + ")");
        // the wire: the real pictures of the post and the launcher
        int[] size = new int[1];
        NBTTagCompound snap = kp.snapshot();
        NetADSVA.Data r = wire(new NetADSVA.Data(NetADSVA.D_KP, snap), size);
        NBTTagCompound ls = g1.snapshot();
        int[] size2 = new int[1];
        NetADSVA.Data rl = wire(new NetADSVA.Data(NetADSVA.D_LAUNCHER, ls), size2);
        check(r != null && r.kind == NetADSVA.D_KP && r.data.equals(snap) && snap.getTagList("Tracks", 10).tagCount() >= 9 && rl != null && rl.data.equals(ls),
                "ad ops world (7.5): the post's picture (" + snap.getTagList("Tracks", 10).tagCount() + " tracks, " + size[0] + " bytes) and the launcher's ("
                        + size2[0] + " bytes) through NetADSVA's wire format and back - the same");
    }

    /** The picture at its biggest: 16 zones, a full log of long lines - still one packet (its gzipped NBT has a 16-bit length). */
    private void netStress(World w) {
        zones0 = new ArrayList<ZoneMath.Zone>(kp.zones());
        List<ZoneMath.Zone> zs = new ArrayList<ZoneMath.Zone>();
        for (int i = 0; i < ZoneMath.MAX_ZONES; i++) {
            ZoneMath.Zone z = new ZoneMath.Zone();
            z.name = "ЗОНА-" + i + "-СЕВЕР";
            z.type = i % 4;
            z.shape = i % 2 == 0 ? ZoneMath.CIRCLE : ZoneMath.RECT;
            z.cx = x0 + i * 7;
            z.cz = z0 - i * 5;
            z.r = 20 + i;
            z.x0 = x0 - 30 - i;
            z.z0 = z0 - 30;
            z.x1 = x0 + 30 + i;
            z.z1 = z0 + 30 + i;
            z.rot = i * 15;
            z.alarm = false;
            zs.add(z);
        }
        kp.setZones(zs);
        for (int i = 0; i < 45; i++) {
            kp.log("sva.ad.log.msg", "B-" + (10 + i), "Борт " + i + ": покиньте зону " + (1000 + i * 37) + ", курс " + (i * 8 % 360) + " - иначе огонь ПВО КП");
        }
        int[] size = new int[1];
        NBTTagCompound snap = kp.snapshot();
        NetADSVA.Data r = wire(new NetADSVA.Data(NetADSVA.D_KP, snap), size);
        check(r != null && r.data.equals(snap) && snap.getTagList("Zones", 10).tagCount() >= ZoneMath.MAX_ZONES,
                "ad ops world (7.5): the post's biggest picture (16 zones, a full log, the tracks: " + size[0] + " bytes) through the wire and back - the same");
        kp.setZones(zones0);
    }

    // ---------------------------------------------------------------- 7.4: Shift+RMB, pick-block

    private int[] partOf(TileEntityAirDefenseSVA m) {
        int[][] c = m.cells();
        for (int i = 1; i < c.length; i++) {
            int x = m.xCoord + c[i][0], y = m.yCoord + c[i][1], z = m.zCoord + c[i][2];
            if (m.getWorldObj().getTileEntity(x, y, z) instanceof TileEntityAdPartSVA) {
                return new int[] {x, y, z};
            }
        }
        return null;
    }

    private void ammo(World w) {
        FakePlayer p = player(w, OWNER_NAME);
        p.setSneaking(true);
        p.inventory.currentItem = 0;
        ItemStack s = ModContentSVA.ammo.stackOf(ItemAmmoSVA.SHELLS, 8);
        p.inventory.setInventorySlotContents(0, s);
        int a0 = g2.ammo();
        p.theItemInWorldManager.activateBlockOrUseItem(p, w, s, g2.xCoord, g2.yCoord, g2.zCoord, 1, 0.5F, 1F, 0.5F);
        int a1 = g2.ammo();
        boolean used = p.inventory.getStackInSlot(0) == null;
        int[] part = partOf(g2);
        ItemStack s2 = ModContentSVA.ammo.stackOf(ItemAmmoSVA.SHELLS, 4);
        p.inventory.setInventorySlotContents(0, s2);
        if (part != null) {
            p.theItemInWorldManager.activateBlockOrUseItem(p, w, s2, part[0], part[1], part[2], 1, 0.5F, 1F, 0.5F);
        }
        int a2 = g2.ammo();
        Item am = ModContentSVA.ammo;
        boolean others = !am.doesSneakBypassUse(w, radar.xCoord, radar.yCoord, radar.zCoord, p) && !am.doesSneakBypassUse(w, kp.xCoord, kp.yCoord, kp.zCoord, p)
                && !am.doesSneakBypassUse(w, x0 + 40, Y, z0 + 40, p) && part != null && am.doesSneakBypassUse(w, part[0], part[1], part[2], p);
        p.setSneaking(false);
        p.inventory.setInventorySlotContents(0, null);
        check(a1 == a0 + 8 && used && a2 == a1 + 4 && others,
                "ad ops world (7.4): Shift+RMB with shells through the player's real path loads the gun - on its master (" + a0 + " -> " + a1 + ") and on a part ("
                        + a2 + "); not the radar, the post or the ground");
    }

    private void pickBlock(World w) {
        int[] part = partOf(g1);
        boolean ok = false;
        String why = "no part";
        if (part != null) {
            TileEntityAdPartSVA real = (TileEntityAdPartSVA) w.getTileEntity(part[0], part[1], part[2]);
            int[] pos = new int[3];
            NBTTagCompound wire;
            try {
                wire = SelfTestAdDocsSVA.wireTag((S35PacketUpdateTileEntity) real.getDescriptionPacket(), pos);
            } catch (java.io.IOException e) {
                wire = null;
            }
            TileEntityAdPartSVA client = new TileEntityAdPartSVA();
            client.xCoord = part[0];
            client.yCoord = part[1];
            client.zCoord = part[2];
            client.setWorldObj(w);
            boolean before = client.master() == null;
            client.readLink(wire);                                  // what onDataPacket does with the packet's tag on the client
            ItemStack pick = ((BlockAdPartSVA) ModContentSVA.adPart).getPickBlock(null, w, part[0], part[1], part[2]);
            ok = before && wire != null && pos[0] == part[0] && pos[1] == part[1] && pos[2] == part[2] && client.master() == g1 && pick != null && pick.getItem() == Item.getItemFromBlock(ModContentSVA.launcher)
                    && pick.getItemDamage() == TileEntityLauncherSVA.GUN;
            why = "linked " + client.isLinked() + ", pick " + pick;
        }
        check(ok, "ad ops world (7.3 / 7.4): a part's description packet gives a fresh (client) tile its master; the part's pick-block is the gun (" + why + ")");
    }

    // ---------------------------------------------------------------- the damage, the limits

    private int shell(World w, EntityVehicleSVA v) {
        double[] c = AirDefenseSVA.centre(v);
        double sx = c[0] - 6, sy = c[1], sz = c[2];
        double[] d = BallisticsMath.dir(sx, sy, sz, c[0], c[1], c[2]);
        AirDefenseSVA.Projectile p = new AirDefenseSVA.Projectile();
        p.x = sx;
        p.y = sy;
        p.z = sz;
        p.vx = d[0] * BallisticsMath.GUN_SPEED;
        p.vy = d[1] * BallisticsMath.GUN_SPEED;
        p.vz = d[2] * BallisticsMath.GUN_SPEED;
        p.life = BallisticsMath.SHELL_LIFE;
        p.target = v.getEntityId();
        p.dim = v.dimension;
        p.damage = (float) BallisticsMath.SHELL_DAMAGE;
        p.launcher = "";
        return AirDefenseSVA.INSTANCE.debugFly((WorldServer) w, p, 10);
    }

    private void damage(World w) {
        float d0 = ConfigSVA.adDamage;
        boolean wear0 = ConfigSVA.wear;
        float[] mul = {1F, 0.5F, 0F, 1F};
        float[] lost = new float[mul.length];
        boolean hits = true;
        try {
            for (int i = 0; i < mul.length; i++) {
                ConfigSVA.adDamage = mul[i];
                ConfigSVA.wear = i < 3;
                float hp = pDmg.state.hp;
                hits &= shell(w, pDmg) == AirDefenseSVA.HIT;
                lost[i] = hp - pDmg.state.hp;
            }
        } finally {
            ConfigSVA.adDamage = d0;
            ConfigSVA.wear = wear0;
        }
        check(hits && Math.abs(lost[0] - BallisticsMath.SHELL_DAMAGE) < 0.01 && Math.abs(lost[1] - lost[0] * 0.5F) < 0.01 && lost[2] == 0F && lost[3] == 0F,
                "ad ops world (7.5, 4.6): airdefense.damageMultiplier on a shell's hit - x1 " + lost[0] + ", x0.5 " + lost[1] + ", x0 " + lost[2]
                        + "; vehicles.wear off - no damage at all (" + lost[3] + ")");
    }

    private void limits(World w) {
        int ml = ConfigSVA.adMaxLaunchers, mr = ConfigSVA.adMaxRadars;
        FakePlayer p = player(w, OWNER_NAME), other = player(w, "TestADOx");
        p.capabilities.isCreativeMode = false;
        other.capabilities.isCreativeMode = false;
        boolean l2, l3, l0, r1, r2, oth, kpNo, cr, placed, nothing;
        int px = x0 + 30, pz = z0 - 10;
        try {
            ConfigSVA.adMaxLaunchers = 2;
            l2 = BlockAirDefenseSVA.overLimit(w, BlockAirDefenseSVA.LAUNCHER, p);
            ConfigSVA.adMaxLaunchers = 3;
            l3 = BlockAirDefenseSVA.overLimit(w, BlockAirDefenseSVA.LAUNCHER, p);
            ConfigSVA.adMaxLaunchers = 0;
            l0 = BlockAirDefenseSVA.overLimit(w, BlockAirDefenseSVA.LAUNCHER, p);
            ConfigSVA.adMaxRadars = 1;
            r1 = BlockAirDefenseSVA.overLimit(w, BlockAirDefenseSVA.RADAR, p);
            oth = BlockAirDefenseSVA.overLimit(w, BlockAirDefenseSVA.RADAR, other);
            kpNo = BlockAirDefenseSVA.overLimit(w, BlockAirDefenseSVA.COMMAND_POST, p);
            p.capabilities.isCreativeMode = true;
            cr = BlockAirDefenseSVA.overLimit(w, BlockAirDefenseSVA.RADAR, p);
            p.capabilities.isCreativeMode = false;
            ConfigSVA.adMaxRadars = 2;
            r2 = BlockAirDefenseSVA.overLimit(w, BlockAirDefenseSVA.RADAR, p);
            ConfigSVA.adMaxRadars = 1;
            ItemStack rs = new ItemStack(ModContentSVA.radar, 1, RadarMath.SURVEY);
            placed = ((ItemBlockAirDefenseSVA) rs.getItem()).placeBlockAt(rs, p, w, px, Y + 1, pz, 1, 0.5F, 1F, 0.5F, RadarMath.SURVEY);
            nothing = w.getBlock(px, Y + 1, pz) == Blocks.air;
        } finally {
            ConfigSVA.adMaxLaunchers = ml;
            ConfigSVA.adMaxRadars = mr;
            p.capabilities.isCreativeMode = false;
        }
        check(l2 && !l3 && !l0 && r1 && !r2 && !oth && !kpNo && !cr && !placed && nothing,
                "ad ops world (7.5): the per-player limits - 2 guns at a limit of 2 over, of 3 / 0 (none) not; 1 radar at 1 over, at 2 not; another "
                        + "player's own count, the command post and creative never; a radar item placed over the limit - refused, nothing built");
    }

    /** A broken launcher frees its place under the limit (its node goes). */
    private void freed(World w) {
        int ml = ConfigSVA.adMaxLaunchers;
        FakePlayer p = player(w, OWNER_NAME);
        boolean before, after;
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        g2.noDrop = true;
        w.setBlockToAir(g2.xCoord, g2.yCoord, g2.zCoord);
        try {
            before = d.count(OWNER_NAME, AirDefenseDataSVA.N_LAUNCHER) == 1;
            ConfigSVA.adMaxLaunchers = 2;
            after = !BlockAirDefenseSVA.overLimit(w, BlockAirDefenseSVA.LAUNCHER, p);      // two were over a limit of 2 (limits())
            ConfigSVA.adMaxLaunchers = 1;
            after &= BlockAirDefenseSVA.overLimit(w, BlockAirDefenseSVA.LAUNCHER, p);      // the one left fills a limit of 1
        } finally {
            ConfigSVA.adMaxLaunchers = ml;
        }
        check(before && after, "ad ops world (7.5): a launcher broken - its place under the limit is free again (one left: at a limit of 1 full, of 2 not)");
    }

    // ---------------------------------------------------------------- airdefense.enabled = false

    /**
     * The global switch is flipped and put back inside this one call - the other tests' sites (same server thread) never
     * see it; meanwhile this site's blocks are ticked by hand (fed every tick: the switch, not the energy, stops them; a
     * command post works every 10th tick, a launcher every 5th).
     */
    private void disabledSync(World w) {
        cmd(op, "alarm", null, null, null);                         // the manual alarm on: it must go off with the power
        check(kp.isAlarm(), "ad ops world (enabled=false): the manual alarm on before the switch");
        TileEntityAirDefenseSVA[] all = {kp, radar, g1, g2};
        boolean ok;
        String mode;
        try {
            ConfigSVA.adEnabled = false;
            disabled = true;
            for (int i = 0; i < 20; i++) {
                for (TileEntityAirDefenseSVA te : all) {
                    te.fillForTest();
                    te.updateEntity();
                }
            }
            ok = !kp.isPowered() && !radar.isPowered() && !g1.isPowered() && !g2.isPowered() && kp.tracks().isEmpty() && radar.contacts().isEmpty()
                    && g1.engagements().isEmpty() && g2.engagements().isEmpty() && !kp.isAlarm() && redstone(w) == 0;
            mode = kp.snapshot().getString("Mode");
        } finally {
            ConfigSVA.adEnabled = true;
            disabled = false;
        }
        check(ok && "off".equals(mode), "ad ops world (airdefense.enabled=false): every block unpowered though fed, no tracks or contacts, no "
                + "engagements, the alarm and its redstone off, the screen says «" + mode + "»");
        for (int i = 0; i < 2; i++) {
            for (TileEntityAirDefenseSVA te : all) {
                te.fillForTest();
                te.updateEntity();
            }
        }
        check(kp.isPowered() && radar.isPowered() && g1.isPowered() && !"off".equals(kp.snapshot().getString("Mode")),
                "ad ops world (airdefense.enabled on again): the blocks are powered again");
    }

    // ---------------------------------------------------------------- build

    private static FakePlayer player(World w, String name) {
        FakePlayer p = FakePlayerFactory.get((WorldServer) w, new GameProfile(UUID.nameUUIDFromBytes(("sva-test:" + name).getBytes()), name));
        p.capabilities.isCreativeMode = false;
        return p;
    }

    private EntityVehicleSVA plane(World w, double x, double z, String name, int mode, int code) {
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.energy = st.maxEnergy();
        ItemStack is = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st);
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, is, x, FLY, z, 0F, STRANGER, name);
        if (v != null) {
            planes.add(v);
            hold.add(new double[] {x, FLY, z});
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
        x0 = (sp.posX + 10800) >> 4 << 4;
        z0 = (sp.posZ - 10800) >> 4 << 4;
        keepLoaded(w);
        area(w, true);
        kp = block(w, x0, z0, ModContentSVA.commandPost, 2, TileEntityCommandPostSVA.class);
        radar = block(w, x0, z0 + 45, ModContentSVA.radar, RadarMath.SURVEY, TileEntityRadarSVA.class);
        g1 = block(w, x0 + 20, z0 + 20, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        g2 = block(w, x0 - 20, z0 + 20, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, TileEntityLauncherSVA.class);
        check(kp != null && radar != null && g1 != null && g2 != null, "ad ops world: a command post, a surveillance radar and two guns placed");
        if (kp == null || radar == null || g1 == null || g2 == null) {
            kp = null;
            return;
        }
        for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {radar, g1, g2}) {
            te.setKpLink(kp.key());
        }
        g1.debugSettings(RoeMath.KP_MARKS, true, false, 2);
        g2.debugSettings(RoeMath.KP_MARKS, true, false, 2);
        g1.debugAmmo(ModContentSVA.ammo.stackOf(ItemAmmoSVA.SHELLS, 32));
        g2.debugAmmo(ModContentSVA.ammo.stackOf(ItemAmmoSVA.SHELLS, 32));
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.name = "O-1";
        z.type = ZoneMath.FORBIDDEN;
        z.shape = ZoneMath.CIRCLE;
        z.cx = x0;
        z.cz = z0 + 20;
        z.r = 45;                                                   // every plane in it but the damage target
        z.alarm = false;                                            // the alarm here is the operator's (the manual one)
        List<ZoneMath.Zone> zs = new ArrayList<ZoneMath.Zone>();
        zs.add(z);
        kp.setZones(zs);
        kp.relinkNow();
        // the strangers in mode C: a surveillance radar alone gives no altitude, and a gun takes only a track whose height it knows
        s1 = plane(w, x0 + 22.5, z0 + 24.5, "Strange1", XpdrMath.C, XpdrMath.VFR);
        s2 = plane(w, x0 - 21.5, z0 + 24.5, "Strange2", XpdrMath.C, XpdrMath.VFR);
        s3 = plane(w, x0 + 0.5, z0 + 8.5, "Strange3", XpdrMath.C, XpdrMath.VFR);
        pOwn = plane(w, x0 + 10.5, z0 + 32.5, "OwnCoder", XpdrMath.A, OWN_CODE);
        pWhite = plane(w, x0 - 10.5, z0 + 32.5, "WhiteOwner", XpdrMath.S, XpdrMath.VFR);
        pWrong = plane(w, x0 + 32.5, z0 + 6.5, "WrongOwner", XpdrMath.S, XpdrMath.VFR);
        pBare = plane(w, x0 - 32.5, z0 + 6.5, "BareOwner", XpdrMath.S, XpdrMath.VFR);
        p7700 = plane(w, x0 + 0.5, z0 + 38.5, "Mayday", XpdrMath.A, XpdrMath.EMERGENCY);
        pDay = plane(w, x0 + 15.5, z0 - 4.5, "Daycard", XpdrMath.A, XpdrMath.VFR);
        pDmg = plane(w, x0 + 35.5, z0 + 50.5, "Target", XpdrMath.A, XpdrMath.VFR);
        if (planes.size() != 10) {
            check(false, "ad ops world: the test aircraft placed");
            kp = null;
        }
    }

    /**
     * Clears the box and lays stone pads only under the four structures and the one «ground» block - a whole floor at Y 150
     * relit every column under it (seconds of lag on a server already full of tests); the planes are held in the air anyway.
     */
    private void area(World w, boolean build) {
        for (int x = x0 - 42; x <= x0 + 42; x++) {
            for (int z = z0 - 16; z <= z0 + 55; z++) {
                for (int y = Y + 1; y <= Y + 10; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
            }
        }
        int[][] pads = {{x0, z0}, {x0, z0 + 45}, {x0 + 20, z0 + 20}, {x0 - 20, z0 + 20}};
        for (int[] p : pads) {
            for (int x = p[0] - 3; x <= p[0] + 3; x++) {
                for (int z = p[1] - 3; z <= p[1] + 3; z++) {
                    w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
                }
            }
        }
        w.setBlock(x0 + 40, Y, z0 + 40, build ? Blocks.stone : Blocks.air, 0, 2);
    }

    private void keepLoaded(World w) {
        if (!(w instanceof WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 48) >> 4; cx <= (x0 + 48) >> 4; cx++) {
            for (int cz = (z0 - 24) >> 4; cz <= (z0 + 62) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void finish(World w) {
        if (disabled) {
            ConfigSVA.adEnabled = true;
            disabled = false;
        }
        for (EntityVehicleSVA v : planes) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        planes.clear();
        hold.clear();
        for (TileEntityAirDefenseSVA te : new TileEntityAirDefenseSVA[] {kp, radar, g1, g2}) {
            if (te != null) {
                te.noDrop = true;
            }
        }
        if (x0 != 0 || z0 != 0) {
            keepLoaded(w);
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
            if (disabled) {
                ConfigSVA.adEnabled = true;
            }
            done = true;
        }
    }
}
